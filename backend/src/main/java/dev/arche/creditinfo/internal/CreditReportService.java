package dev.arche.creditinfo.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.common.BusinessException;
import dev.arche.customer.CustomerView;
import dev.arche.customer.Customers;
import dev.arche.integration.CreditReportingPort;
import dev.arche.servicing.AccountRef;
import dev.arche.servicing.LoanAccounts;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

interface CreditReportFileRepository extends JpaRepository<CreditReportFile, Long> {

	boolean existsByBaseDate(LocalDate baseDate);

	List<CreditReportFile> findTop100ByOrderByBaseDateDesc();

	boolean existsByStatusNot(CreditReportFile.Status status);
}

interface CreditReportRecordRepository extends JpaRepository<CreditReportRecord, Long> {

	List<CreditReportRecord> findByFileIdOrderBySeq(long fileId);
}

interface CreditReportStateRepository extends JpaRepository<CreditReportState, Long> {

	Optional<CreditReportState> findByAccountId(long accountId);

	List<CreditReportState> findByClosedFalse();
}

/**
 * 신용정보 보고 일 배치 (guide 11.1·12.2, R61): 파일 생성 → 전송 → 결과 파일 반영.
 * 정상 결과를 받은 레코드만 "기관에 반영된 상태"로 올리므로, 오류 레코드는 다음 보고일에 다시 나간다.
 * 앞 파일의 결과를 받기 전에는 다음 파일을 만들지 않는다(같은 사유를 두 번 보내지 않게).
 */
@Service
class CreditReportService {

	private static final Logger log = LoggerFactory.getLogger(CreditReportService.class);

	private final CreditReportFileRepository files;
	private final CreditReportRecordRepository records;
	private final CreditReportStateRepository states;
	private final CreditReportingPort port;
	private final LoanAccounts accounts;
	private final Customers customers;
	private final BusinessDates businessDates;
	private final Clock clock;
	private final TransactionTemplate tx;

	CreditReportService(CreditReportFileRepository files, CreditReportRecordRepository records,
			CreditReportStateRepository states, CreditReportingPort port, LoanAccounts accounts, Customers customers,
			BusinessDates businessDates, Clock clock, PlatformTransactionManager txm) {
		this.files = files;
		this.records = records;
		this.states = states;
		this.port = port;
		this.accounts = accounts;
		this.customers = customers;
		this.businessDates = businessDates;
		this.clock = clock;
		this.tx = new TransactionTemplate(txm);
	}

	/** 보고 기준일 기본값: 마지막으로 마감한 업무일 (업무일자 − 1일) */
	LocalDate defaultBaseDate() {
		return businessDates.today().minusDays(1);
	}

	@Transactional
	CreditReportFile generate(LocalDate baseDate) {
		if (baseDate.isAfter(businessDates.today())) {
			throw new BusinessException("BASE_DATE_INVALID", "보고 기준일은 업무일자 이후일 수 없습니다");
		}
		if (files.existsByBaseDate(baseDate)) {
			throw new BusinessException("DUPLICATE", baseDate + " 보고 파일이 이미 있습니다");
		}
		if (files.existsByStatusNot(CreditReportFile.Status.RESULT_RECEIVED)) {
			throw new BusinessException("PREVIOUS_PENDING", "앞 보고 파일의 결과를 먼저 반영하세요");
		}
		Map<Long, AccountRef> targets = new LinkedHashMap<>();
		accounts.openAccounts().forEach(a -> targets.put(a.id(), a));
		for (CreditReportState s : states.findByClosedFalse()) {
			targets.computeIfAbsent(s.accountId(), accounts::get);
		}
		List<CreditReportingPort.ReportItem> items = new ArrayList<>();
		List<Object[]> planned = new ArrayList<>();
		for (AccountRef a : targets.values()) {
			LoanAccounts.MessagingView v = accounts.messagingView(a.id());
			ReportPlanner.Reported reported = states.findByAccountId(a.id()).map(CreditReportState::reported)
					.orElse(ReportPlanner.Reported.NONE);
			List<ReportPlanner.Planned> plan = ReportPlanner.plan(new ReportPlanner.Snapshot(a.id(),
					v.delinquencyStartedOn(), a.paidOffOn(), a.principalBalance()), reported, baseDate);
			if (plan.isEmpty()) {
				continue;
			}
			CustomerView c = customers.get(v.customerId());
			for (ReportPlanner.Planned p : plan) {
				int seq = items.size() + 1;
				items.add(new CreditReportingPort.ReportItem(seq, p.type(), a.accountNo(), c.customerNo(), c.name(),
						p.eventDate(), p.balance(), p.delinquencyDays()));
				planned.add(new Object[] { a.id(), p });
			}
		}
		CreditReportingPort.GeneratedFile g = port.generate(baseDate, items);
		CreditReportFile file = files.save(new CreditReportFile(baseDate, g.fileName(), g.recordCount(), g.byteLength()));
		for (int i = 0; i < planned.size(); i++) {
			ReportPlanner.Planned p = (ReportPlanner.Planned) planned.get(i)[1];
			records.save(new CreditReportRecord(file.getId(), i + 1, (Long) planned.get(i)[0], p.type(), p.eventDate(),
					p.balance(), p.delinquencyDays()));
		}
		return file;
	}

	@Transactional
	CreditReportFile send(long fileId) {
		CreditReportFile f = file(fileId);
		if (f.status() != CreditReportFile.Status.GENERATED) {
			throw new BusinessException("ALREADY_SENT", "이미 전송한 파일입니다");
		}
		CreditReportingPort.SendReceipt r = port.send(f.fileName());
		f.sent(r.receiptNo(), OffsetDateTime.now(clock));
		return f;
	}

	/** 결과 파일 반영. 아직 결과가 없으면 RESULT_NOT_READY */
	@Transactional
	CreditReportFile applyResult(long fileId) {
		CreditReportFile f = file(fileId);
		if (f.status() != CreditReportFile.Status.SENT) {
			throw new BusinessException("NOT_SENT", "전송 후 결과를 반영합니다");
		}
		List<CreditReportingPort.ItemResult> results = port.fetchResult(f.fileName())
				.orElseThrow(() -> new BusinessException("RESULT_NOT_READY", "결과 파일이 아직 없습니다"));
		Map<Integer, CreditReportingPort.ItemResult> bySeq = new LinkedHashMap<>();
		results.forEach(r -> bySeq.put(r.seq(), r));
		int errors = 0;
		for (CreditReportRecord rec : records.findByFileIdOrderBySeq(fileId)) {
			CreditReportingPort.ItemResult r = bySeq.get(rec.seq());
			if (r == null) {
				rec.result("NONE", "결과 없음 — 다음 보고일에 다시 보냄");
				errors++;
				continue;
			}
			rec.result(r.resultCode(), r.message());
			if (r.ok()) {
				CreditReportState s = states.findByAccountId(rec.accountId())
						.orElseGet(() -> states.save(new CreditReportState(rec.accountId())));
				s.apply(rec.recordType(), f.baseDate());
			} else {
				errors++;
			}
		}
		f.resultReceived(errors, OffsetDateTime.now(clock));
		return f;
	}

	/** 일 배치: 생성 → 전송 → 결과 반영 (Mock은 전송 즉시 결과 파일이 생긴다) */
	CreditReportFile runDaily(LocalDate baseDate) {
		CreditReportFile f = tx.execute(s -> generate(baseDate));
		tx.execute(s -> send(f.getId()));
		try {
			return tx.execute(s -> applyResult(f.getId()));
		} catch (BusinessException e) {
			if ("RESULT_NOT_READY".equals(e.code())) {
				return tx.execute(s -> file(f.getId()));
			}
			throw e;
		}
	}

	/** 매일 23:30 (23:00 일마감 뒤) */
	@Scheduled(cron = "${arche.credit-report.cron:0 30 23 * * *}", zone = "Asia/Seoul")
	@SchedulerLock(name = "credit-report-daily", lockAtMostFor = "PT30M")
	void scheduled() {
		try {
			LocalDate base = defaultBaseDate();
			if (!tx.execute(s -> files.existsByBaseDate(base))) {
				runDaily(base);
			}
		} catch (RuntimeException e) {
			log.error("신용정보 보고 배치 실패", e);
		}
	}

	CreditReportFile file(long id) {
		return files.findById(id).orElseThrow(() -> new BusinessException("REPORT_NOT_FOUND", "보고 파일이 없습니다"));
	}
}
