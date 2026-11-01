package dev.arche.reporting.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.common.BusinessException;
import dev.arche.payment.Payments;
import dev.arche.reporting.DailyReports;
import dev.arche.servicing.ServicingReports;
import dev.arche.servicing.ServicingReports.AccountBalance;
import dev.arche.servicing.ServicingReports.LedgerLine;
import dev.arche.staff.CurrentStaff;
import dev.arche.staff.StaffDirectory;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 일보·월마감 스냅샷(R65·R66)과 보고서 조회. 마감된 날은 스냅샷을, 마감 전인 날은 같은 계산으로 만든 미리보기를 돌려준다.
 */
@Service
class ReportService implements DailyReports {

	private static final Logger log = LoggerFactory.getLogger(ReportService.class);

	/**
	 * @param closed            마감 스냅샷이면 true, 미리보기면 false
	 * @param balancesAvailable 잔액·상태·구간 행이 있는지 (기능 도입 전에 마감된 과거 날짜는 원장 행만 있음)
	 */
	record DailyView(LocalDate businessDate, boolean closed, boolean balancesAvailable,
			@Nullable OffsetDateTime snapshotAt, long journalDebit, long journalCredit, int unmappedCount,
			List<DailyReportBuilder.Line> lines) {
	}

	record JournalView(LocalDate businessDate, boolean closed, List<JournalMapper.Line> lines,
			List<JournalMapper.AccountTotal> totals, long debit, long credit, boolean balanced,
			List<JournalMapper.Unmapped> unmapped, boolean matchesSnapshot, List<ReportStore.Account> accounts) {
	}

	record RollRateView(YearMonth month, YearMonth previousMonth, boolean previousAvailable, boolean currentClosed,
			@Nullable LocalDate asOf, RollRate.Result result) {
	}

	record ProvisionView(@Nullable YearMonth month, LocalDate asOf, boolean closed, Provision.Result result) {
	}

	private final ServicingReports servicing;
	private final Payments payments;
	private final ReportStore store;
	private final StaffDirectory staff;
	private final BusinessDates businessDates;
	private final Clock clock;

	ReportService(ServicingReports servicing, Payments payments, ReportStore store, StaffDirectory staff,
			BusinessDates businessDates, Clock clock) {
		this.servicing = servicing;
		this.payments = payments;
		this.store = store;
		this.staff = staff;
		this.businessDates = businessDates;
		this.clock = clock;
	}

	// ------------------------------------------------------------------ 일마감 훅

	@Override
	@Transactional
	public Snapshot snapshot(LocalDate d) {
		List<LedgerLine> entries = servicing.ledgerPostedOn(d);
		List<AccountBalance> balances = servicing.balances();
		List<DailyReportBuilder.Line> lines = lines(d, entries, balances);
		JournalMapper.Day journal = new JournalMapper(store.rules()).day(entries);
		OffsetDateTime now = OffsetDateTime.now(clock);
		String by = staff.currentIfAny().map(CurrentStaff::loginId).orElse("batch");
		store.replaceDaily(new ReportStore.DailyHeader(d, now, by, journal.debit(), journal.credit(),
				journal.unmapped().size()), lines, journal.totals());
		if (!journal.balanced()) {
			log.warn("일보 {} 분개 차변 {} ≠ 대변 {}", d, journal.debit(), journal.credit());
		}
		if (!journal.unmapped().isEmpty()) {
			log.warn("일보 {} 분개 매핑 없는 거래유형: {}", d, journal.unmapped());
		}
		boolean monthEnd = d.plusDays(1).getDayOfMonth() == 1;
		if (monthEnd) {
			store.replaceMonthEnd(YearMonth.from(d), d, now, by, monthRows(balances));
		}
		return new Snapshot(d, lines.size(), journal.debit(), journal.credit(), journal.unmapped().size(), monthEnd);
	}

	private List<DailyReportBuilder.Line> lines(LocalDate d, List<LedgerLine> entries, List<AccountBalance> balances) {
		Payments.DailyTotals t = payments.totalsFor(d);
		List<DailyReportBuilder.Line> lines = new ArrayList<>(DailyReportBuilder.ledgerLines(d, entries));
		lines.addAll(DailyReportBuilder.balanceLines(balances, new DailyReportBuilder.PaymentTotals(
				t.allocatedCount(), t.allocatedTotal().won(), t.unmatchedOpenTotal().won())));
		return lines;
	}

	static List<ReportStore.MonthRow> monthRows(List<AccountBalance> balances) {
		List<ReportStore.MonthRow> rows = new ArrayList<>();
		for (AccountBalance a : balances) {
			Bucket b = Bucket.of(a.status(), a.delinquencyDays());
			if (b.open()) {
				rows.add(new ReportStore.MonthRow(a.accountId(), a.accountNo(), a.productCode(), a.status(), b,
						a.delinquencyDays(), a.principalBalance(), Provision.claim(a)));
			}
		}
		return rows;
	}

	// ------------------------------------------------------------------ 조회

	@Transactional(readOnly = true)
	DailyView daily(LocalDate d) {
		LocalDate today = businessDates.today();
		if (d.isAfter(today)) {
			throw new BusinessException("FUTURE_DATE", "업무일자(" + today + ") 이후 날짜의 일보는 없습니다");
		}
		var snapshot = store.daily(d);
		if (snapshot.isPresent()) {
			var h = snapshot.get();
			return new DailyView(d, true, true, h.createdAt(), h.journalDebit(), h.journalCredit(), h.unmappedCount(),
					store.dailyLines(d));
		}
		List<LedgerLine> entries = servicing.ledgerPostedOn(d);
		JournalMapper.Day journal = new JournalMapper(store.rules()).day(entries);
		boolean live = d.equals(today);
		List<DailyReportBuilder.Line> lines = live ? lines(d, entries, servicing.balances())
				: DailyReportBuilder.ledgerLines(d, entries);
		return new DailyView(d, false, live, null, journal.debit(), journal.credit(), journal.unmapped().size(),
				lines);
	}

	@Transactional(readOnly = true)
	JournalView journal(LocalDate d) {
		LocalDate today = businessDates.today();
		if (d.isAfter(today)) {
			throw new BusinessException("FUTURE_DATE", "업무일자(" + today + ") 이후 날짜의 분개는 없습니다");
		}
		JournalMapper.Day live = new JournalMapper(store.rules()).day(servicing.ledgerPostedOn(d));
		var snapshot = store.daily(d);
		List<JournalMapper.AccountTotal> totals = live.totals();
		long debit = live.debit();
		long credit = live.credit();
		boolean matches = true;
		if (snapshot.isPresent()) {
			// 마감된 날의 일계는 마감 시점 값 (R68). 현재 매핑으로 다시 만든 행과 다르면 표시만 한다
			totals = store.journalTotals(d);
			debit = snapshot.get().journalDebit();
			credit = snapshot.get().journalCredit();
			// DB 정렬(collation)과 Java 정렬이 다를 수 있어 집합으로 비교
			matches = new java.util.HashSet<>(totals).equals(new java.util.HashSet<>(live.totals()));
		}
		return new JournalView(d, snapshot.isPresent(), live.lines(), totals, debit, credit, debit == credit,
				live.unmapped(), matches, store.accounts());
	}

	@Transactional(readOnly = true)
	List<ReportStore.MonthHeader> months() {
		return store.months();
	}

	/** 당월 스냅샷이 없으면(월말 전) 현재 잔액으로 미리보기 */
	@Transactional(readOnly = true)
	RollRateView rollRate(YearMonth month) {
		YearMonth prev = month.minusMonths(1);
		var prevHeader = store.month(prev);
		var header = store.month(month);
		List<RollRate.Position> previous = positions(store.monthRows(prev));
		List<RollRate.Position> current;
		LocalDate asOf;
		if (header.isPresent()) {
			current = positions(store.monthRows(month));
			asOf = header.get().asOf();
		} else if (YearMonth.from(businessDates.today()).equals(month)) {
			current = positions(monthRows(servicing.balances()));
			asOf = businessDates.today();
		} else {
			throw new BusinessException("MONTH_SNAPSHOT_NOT_FOUND", month + " 월마감 스냅샷이 없습니다");
		}
		return new RollRateView(month, prev, prevHeader.isPresent(), header.isPresent(), asOf,
				RollRate.of(previous, current));
	}

	private static List<RollRate.Position> positions(List<ReportStore.MonthRow> rows) {
		return rows.stream().map(r -> new RollRate.Position(r.accountId(), r.bucket(), r.claimBalance())).toList();
	}

	/** month가 null이면 현재(마지막 마감 직후 잔액) */
	@Transactional(readOnly = true)
	ProvisionView provision(@Nullable YearMonth month) {
		if (month == null) {
			LocalDate today = businessDates.today();
			List<Provision.Exposure> ex = monthRows(servicing.balances()).stream()
					.map(r -> new Provision.Exposure(r.bucket(), r.claimBalance())).toList();
			return new ProvisionView(null, today, false, Provision.of(ex, store.ratesAt(today)));
		}
		var header = store.month(month)
				.orElseThrow(() -> new BusinessException("MONTH_SNAPSHOT_NOT_FOUND", month + " 월마감 스냅샷이 없습니다"));
		List<Provision.Exposure> ex = store.monthRows(month).stream()
				.map(r -> new Provision.Exposure(r.bucket(), r.claimBalance())).toList();
		return new ProvisionView(month, header.asOf(), true, Provision.of(ex, store.ratesAt(header.asOf())));
	}
}
