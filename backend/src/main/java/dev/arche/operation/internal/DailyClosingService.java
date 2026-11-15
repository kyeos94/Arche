package dev.arche.operation.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.collection.CollectionDaily;
import dev.arche.common.BusinessException;
import dev.arche.payment.Payments;
import dev.arche.reporting.DailyReports;
import dev.arche.servicing.LoanAccounts;
import dev.arche.staff.CurrentStaff;
import dev.arche.staff.StaffDirectory;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

interface DailyClosingRepository extends JpaRepository<DailyClosing, Long> {

	Optional<DailyClosing> findByBusinessDate(LocalDate businessDate);

	List<DailyClosing> findTop30ByOrderByBusinessDateDesc();
}

/**
 * 일마감 (R24). 업무일자 D의 마감은 D까지의 입금을 반영한 뒤 연체·상실을 D+1 날짜로 판정·기록한다.
 * <ol>
 * <li>당일 입금 매칭·충당: 입금은 등록 즉시 충당되므로 여기서는 집계만</li>
 * <li>약정일 도래 건 가수금 충당 · ③ 연체 판정 · ④ 기한이익상실 사유·상실 판정: 열린 계약 전체를 D+1까지 재생</li>
 * <li>(추심) 소멸시효 임박·완성 알림, 분할납부 약속 판정·해제 (G13·G19)</li>
 * <li value="5">잔액 대사: 계약 요약 잔액 = 원장 합계</li>
 * <li>(보고서) 일보 스냅샷, 말일이면 월마감 스냅샷 — 대사 직후·업무일자 변경 전 (R65·R66)</li>
 * <li>업무일자 변경</li>
 * </ol>
 * 각 계약은 자체 트랜잭션으로 처리하므로, 중간에 실패해도 다시 실행하면 같은 결과가 된다(재생은 결정적).
 * <p>
 * 계약 단위 실패 격리 (R76, ADR 0012): ②~④에서 한 계약의 재생이 실패하면 그 계약만 롤백하고 실패 기록(daily_closing_failure)에
 * 남긴 뒤 다음 계약을 계속한다. 실패가 있어도 ⑤ 대사 → 일보 스냅샷("미판정 계약 N건") → 업무일자 변경까지 진행한다.
 * 실패한 계약은 asOf가 그대로라 다음 마감이나 재판정({@link ClosingFailureService#rejudge}) 때 결정적으로 따라잡는다.
 */
@Service
class DailyClosingService {

	private static final Logger log = LoggerFactory.getLogger(DailyClosingService.class);

	private final DailyClosingRepository closings;
	private final BusinessDates businessDates;
	private final LoanAccounts accounts;
	private final Payments payments;
	private final CollectionDaily collectionDaily;
	private final StaffDirectory staff;
	private final DailyReports reports;
	private final ClosingFailureService failures;
	private final Clock clock;

	DailyClosingService(DailyClosingRepository closings, BusinessDates businessDates, LoanAccounts accounts,
			Payments payments, CollectionDaily collectionDaily, StaffDirectory staff, DailyReports reports,
			ClosingFailureService failures, Clock clock) {
		this.closings = closings;
		this.businessDates = businessDates;
		this.accounts = accounts;
		this.payments = payments;
		this.collectionDaily = collectionDaily;
		this.staff = staff;
		this.reports = reports;
		this.failures = failures;
		this.clock = clock;
	}

	/** 매일 23:00 (정책서 7장). 다중 서버에서는 ShedLock으로 한 대만 실행 */
	@Scheduled(cron = "${arche.closing.cron:0 0 23 * * *}", zone = "Asia/Seoul")
	@SchedulerLock(name = "daily-closing", lockAtMostFor = "PT2H")
	void scheduled() {
		try {
			close();
		} catch (RuntimeException e) {
			log.error("일마감 실패", e);
		}
	}

	DailyClosing close() {
		LocalDate d = businessDates.today();
		if (closings.findByBusinessDate(d).isPresent()) {
			throw new BusinessException("ALREADY_CLOSED", d + "은 이미 마감했습니다");
		}
		Payments.DailyTotals totals = payments.totalsFor(d);
		LoanAccounts.JudgeReport judged = accounts.judgeAll(d.plusDays(1));
		int unjudged = failures.record(d, judged.failures());
		if (unjudged > 0) {
			log.error("일마감 {}: 판정 실패 계약 {}건은 롤백하고 마감을 계속합니다 (재판정 필요, R76): {}", d, unjudged,
					judged.failures().stream().map(f -> f.accountId() + "[" + f.code() + "]").toList());
		}
		CollectionDaily.Report collection = collectionDaily.run(d.plusDays(1));
		int mismatches = accounts.reconcile();
		DailyReports.Snapshot report = reports.snapshot(d, unjudged);
		DailyClosing closing = closings.save(new DailyClosing(d, OffsetDateTime.now(clock),
				staff.currentIfAny().map(CurrentStaff::loginId).orElse("batch"), totals.allocatedTotal().won(),
				totals.allocatedCount(), accounts.correctionsPostedOn(d).won(), totals.unmatchedOpenTotal().won(),
				judged.accounts(), judged.newNotices(), judged.accelerations(), mismatches, unjudged));
		businessDates.advance(d);
		log.info("일마감 {} 완료: 계약 {}건 판정, 판정 실패 {}, 신규 통지 {}, 상실 {}, 대사 불일치 {}, 추심 알림 {}·{}·{}, 자동 문자 {}건 예약,"
				+ " 일보 {}행(분개 차변 {} / 대변 {}, 미매핑 {}){}",
				d, judged.accounts(), unjudged, judged.newNotices(), judged.accelerations(), mismatches,
				collection.limitationAlerts(), collection.promiseMisses(), collection.promisesTerminated(),
				collection.messagesScheduled(), report.lines(), report.journalDebit(), report.journalCredit(),
				report.unmappedTypes(), report.monthEnd() ? ", 월마감" : "");
		return closing;
	}

	List<DailyClosing> recent() {
		return closings.findTop30ByOrderByBusinessDateDesc();
	}
}
