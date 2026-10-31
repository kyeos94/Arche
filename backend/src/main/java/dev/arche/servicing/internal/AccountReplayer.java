package dev.arche.servicing.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.common.RepaymentMethod;
import dev.arche.servicing.engine.AccelerationNoticeView;
import dev.arche.servicing.engine.Installment;
import dev.arche.servicing.engine.KnownNotice;
import dev.arche.servicing.engine.LoanEvent;
import dev.arche.servicing.engine.LoanSimulator;
import dev.arche.servicing.engine.LoanTerms;
import dev.arche.servicing.engine.NoticeStatus;
import dev.arche.servicing.engine.Posting;
import dev.arche.servicing.engine.RatePeriod;
import dev.arche.servicing.engine.SimulationResult;
import dev.arche.staff.StaffDirectory;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * 계약 1건을 엔진으로 재생하고 결과를 저장한다 (ADR 0002).
 * <ol>
 * <li>요약 컬럼 갱신</li>
 * <li>회차: (버전, 회차) 단위로 갱신·추가, 결과에 없는 행은 REPLACED</li>
 * <li>원장: 저장된 유효 거래와 재생 결과를 키·금액으로 비교해 달라진 거래만 REVERSAL + 새 거래 (R26)</li>
 * <li>통지: 사유 발생일로 대응, 사유가 없어진 통지는 REVOKED/CANCELLED (R5)</li>
 * </ol>
 * 호출자는 계약 락을 잡은 트랜잭션 안에서 부른다.
 */
@Component
class AccountReplayer {

	record Outcome(SimulationResult result, int newNotices, boolean newlyAccelerated, int reversed, int posted) {
	}

	private final RateTermRepository rateTerms;
	private final DueDayTermRepository dueDayTerms;
	private final AccountEventRepository events;
	private final InstallmentRepository installments;
	private final LedgerEntryRepository ledger;
	private final AccelerationNoticeRepository notices;
	private final AcquisitionRepository acquisitions;
	private final BusinessDates businessDates;
	private final StaffDirectory staff;
	private final Clock clock;

	AccountReplayer(RateTermRepository rateTerms, DueDayTermRepository dueDayTerms, AccountEventRepository events, InstallmentRepository installments,
			LedgerEntryRepository ledger, AccelerationNoticeRepository notices, AcquisitionRepository acquisitions,
			BusinessDates businessDates, StaffDirectory staff, Clock clock) {
		this.rateTerms = rateTerms;
		this.dueDayTerms = dueDayTerms;
		this.events = events;
		this.installments = installments;
		this.ledger = ledger;
		this.notices = notices;
		this.acquisitions = acquisitions;
		this.businessDates = businessDates;
		this.staff = staff;
		this.clock = clock;
	}

	LoanTerms terms(LoanAccount a) {
		if (a.repaymentMethod() == RepaymentMethod.ACQUIRED) {
			AcquisitionRow acq = acquisitions.findByAccountId(a.getId()).orElseThrow();
			return LoanTerms.acquired(a.initialPrincipal(), acq.toEngine());
		}
		List<RatePeriod> periods = rateTerms.findByAccountIdOrderByValidFrom(a.getId()).stream()
				.map(t -> new RatePeriod(t.validFrom(), t.rate())).toList();
		int dueDay = initialDueDay(a);
		return new LoanTerms(a.repaymentMethod(), a.initialPrincipal(), a.disbursedOn(), a.termMonths(),
				a.graceMonths(), dueDay, periods, a.prepaymentFeeRate(), null);
	}

	/** 실행 시 약정일 = 가장 이른 약정일 이력. 이후 변경은 DUE_DAY_CHANGE 이벤트로 재생 */
	private int initialDueDay(LoanAccount a) {
		return dueDayTerms.findByAccountIdOrderByValidFrom(a.getId()).getFirst().dueDay();
	}

	List<LoanEvent> activeEvents(long accountId) {
		return events.findByAccountIdOrderById(accountId).stream().filter(AccountEvent::active)
				.map(AccountEvent::toEngine).toList();
	}

	/** 저장 없이 재생만 (조회·견적용) */
	SimulationResult simulate(LoanAccount a, LoanTerms terms, List<LoanEvent> extra, LocalDate asOf) {
		List<LoanEvent> all = new ArrayList<>(activeEvents(a.getId()));
		all.addAll(extra);
		return new LoanSimulator(businessDates.calendar()).simulate(terms, all, asOf,
				new LoanSimulator.Options(asOf, known(a.getId())));
	}

	private List<KnownNotice> known(long accountId) {
		return notices.findByAccountIdOrderById(accountId).stream().filter(AccelerationNotice::live)
				.map(AccelerationNotice::toKnown).toList();
	}

	Outcome replay(LoanAccount a, LoanTerms terms, LocalDate asOf, LocalDate detectFrom,
			@Nullable Long correctionId) {
		List<AccountEvent> eventRows = events.findByAccountIdOrderById(a.getId());
		Map<String, Long> paymentByRef = new HashMap<>();
		List<LoanEvent> engineEvents = new ArrayList<>();
		for (AccountEvent e : eventRows) {
			if (e.paymentId() != null) {
				paymentByRef.put(e.ref(), e.paymentId());
			}
			if (e.active()) {
				engineEvents.add(e.toEngine());
			}
		}
		List<AccelerationNotice> noticeRows = notices.findByAccountIdOrderById(a.getId());
		List<KnownNotice> known = noticeRows.stream().filter(AccelerationNotice::live)
				.map(AccelerationNotice::toKnown).toList();

		SimulationResult r = new LoanSimulator(businessDates.calendar()).simulate(terms, engineEvents, asOf,
				new LoanSimulator.Options(detectFrom, known));

		boolean wasAccelerated = a.acceleratedOn() != null;
		a.apply(r);
		saveInstallments(a.getId(), r);
		int[] ledgerCounts = saveLedger(a.getId(), r.postings(), paymentByRef, correctionId);
		int created = saveNotices(a.getId(), r.notices(), noticeRows);
		return new Outcome(r, created, !wasAccelerated && r.acceleratedOn() != null, ledgerCounts[0],
				ledgerCounts[1]);
	}

	private void saveInstallments(long accountId, SimulationResult r) {
		Map<String, InstallmentRow> existing = new HashMap<>();
		for (InstallmentRow row : installments.findByAccountIdOrderByScheduleVersionAscSeqAsc(accountId)) {
			existing.put(row.scheduleVersion() + ":" + row.seq(), row);
		}
		Set<String> seen = new HashSet<>();
		List<Installment> all = new ArrayList<>(r.previousVersions());
		all.addAll(r.installments());
		List<InstallmentRow> toSave = new ArrayList<>();
		for (Installment i : all) {
			String key = i.scheduleVersion() + ":" + i.seq();
			seen.add(key);
			InstallmentRow row = existing.get(key);
			if (row == null) {
				toSave.add(new InstallmentRow(accountId, i));
			} else {
				row.update(i);
			}
		}
		existing.forEach((key, row) -> {
			if (!seen.contains(key)) {
				row.markReplaced();
			}
		});
		installments.saveAll(toSave);
	}

	private int[] saveLedger(long accountId, List<Posting> postings, Map<String, Long> paymentByRef,
			@Nullable Long correctionId) {
		List<LedgerEntry> stored = ledger.findByAccountIdOrderById(accountId);
		Set<Long> reversed = new HashSet<>();
		for (LedgerEntry e : stored) {
			if (e.reversalOfId() != null) {
				reversed.add(e.reversalOfId());
			}
		}
		Map<String, Deque<LedgerEntry>> effective = new LinkedHashMap<>();
		for (LedgerEntry e : stored) {
			if (!"REVERSAL".equals(e.type()) && !reversed.contains(e.getId())) {
				effective.computeIfAbsent(e.key(), k -> new ArrayDeque<>()).add(e);
			}
		}
		List<Posting> toPost = new ArrayList<>();
		for (Posting p : postings) {
			Deque<LedgerEntry> candidates = effective.get(p.key());
			LedgerEntry match = candidates == null ? null : candidates.peekFirst();
			if (match != null && match.toPosting().sameAmounts(p)) {
				candidates.removeFirst();
			} else {
				toPost.add(p);
			}
		}
		LocalDate today = businessDates.today();
		OffsetDateTime now = OffsetDateTime.now(clock);
		String actor = staff.currentIfAny().map(s -> s.loginId()).orElse("system");
		List<LedgerEntry> writes = new ArrayList<>();
		int reversals = 0;
		for (Deque<LedgerEntry> leftovers : effective.values()) {
			for (LedgerEntry e : leftovers) {
				writes.add(e.reversal(correctionId, today, now, actor));
				reversals++;
			}
		}
		for (Posting p : toPost) {
			writes.add(LedgerEntry.of(accountId, p, paymentByRef.get(p.sourceRef()), correctionId, today, now,
					actor));
		}
		ledger.saveAll(writes);
		return new int[] { reversals, toPost.size() };
	}

	private int saveNotices(long accountId, List<AccelerationNoticeView> views, List<AccelerationNotice> rows) {
		LocalDate today = businessDates.today();
		Map<LocalDate, AccelerationNotice> live = new HashMap<>();
		for (AccelerationNotice n : rows) {
			if (n.live()) {
				live.put(n.reasonDate(), n);
			}
		}
		int created = 0;
		for (AccelerationNoticeView v : views) {
			AccelerationNotice row = live.remove(v.reasonDate());
			if (v.status() == NoticeStatus.VOID) {
				if (row != null) {
					row.voidOut(today);
				}
			} else if (row != null) {
				row.apply(v);
			} else if (rows.stream().noneMatch(n -> n.reasonDate().equals(v.reasonDate()))) {
				notices.save(new AccelerationNotice(accountId, v));
				created++;
			}
		}
		for (AccelerationNotice orphan : live.values()) {
			orphan.voidOut(today);
		}
		return created;
	}
}
