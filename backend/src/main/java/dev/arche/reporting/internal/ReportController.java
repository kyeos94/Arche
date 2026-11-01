package dev.arche.reporting.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.common.BusinessException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 보고서 조회 API (R65~R68). 개인정보가 없는 회사 전체 집계라 데이터 범위를 적용하지 않는다 (R68 ③).
 */
@RestController
@RequestMapping("/api/reports")
@PreAuthorize("hasAuthority('report.read')")
class ReportController {

	// ------------------------------------------------------------------ DTO

	record LineDto(String section, String sectionLabel, String item, String label, long count, long amount) {

		static LineDto of(DailyReportBuilder.Line l) {
			return new LineDto(l.section(), ReportLabels.section(l.section()), l.item(),
					ReportLabels.item(l.section(), l.item()), l.count(), l.amount());
		}
	}

	record DailyDto(LocalDate businessDate, boolean closed, boolean balancesAvailable, OffsetDateTime snapshotAt,
			long journalDebit, long journalCredit, boolean journalBalanced, int unmappedCount, List<LineDto> lines) {

		static DailyDto of(ReportService.DailyView v) {
			return new DailyDto(v.businessDate(), v.closed(), v.balancesAvailable(), v.snapshotAt(), v.journalDebit(),
					v.journalCredit(), v.journalDebit() == v.journalCredit(), v.unmappedCount(),
					v.lines().stream().map(LineDto::of).toList());
		}
	}

	record JournalLineDto(long entryId, String accountNo, String postingType, String postingLabel, boolean reversal,
			String accountCode, String accountName, long debit, long credit, String memo, boolean unmapped) {
	}

	record AccountTotalDto(String accountCode, String accountName, String category, long debit, long credit) {
	}

	record JournalDto(LocalDate businessDate, boolean closed, long debit, long credit, boolean balanced,
			boolean matchesSnapshot, List<AccountTotalDto> totals, List<JournalMapper.Unmapped> unmapped,
			List<JournalLineDto> lines) {

		static JournalDto of(ReportService.JournalView v) {
			Map<String, ReportStore.Account> accounts = v.accounts().stream()
					.collect(Collectors.toMap(ReportStore.Account::code, a -> a));
			List<AccountTotalDto> totals = v.totals().stream()
					.sorted(java.util.Comparator.comparingInt(
							t -> accounts.containsKey(t.accountCode()) ? accounts.get(t.accountCode()).sortOrder()
									: Integer.MAX_VALUE))
					.map(t -> new AccountTotalDto(t.accountCode(), name(accounts, t.accountCode()),
							accounts.containsKey(t.accountCode()) ? accounts.get(t.accountCode()).category() : "",
							t.debit(), t.credit()))
					.toList();
			List<JournalLineDto> lines = v.lines().stream()
					.map(l -> new JournalLineDto(l.entryId(), l.accountNo(), l.postingType(),
							ReportLabels.item(DailyReportBuilder.POSTING, l.postingType()), l.reversal(),
							l.accountCode(), name(accounts, l.accountCode()), l.debit(), l.credit(), l.memo(),
							l.unmapped()))
					.toList();
			return new JournalDto(v.businessDate(), v.closed(), v.debit(), v.credit(), v.balanced(),
					v.matchesSnapshot(), totals, v.unmapped(), lines);
		}

		private static String name(Map<String, ReportStore.Account> accounts, String code) {
			ReportStore.Account a = accounts.get(code);
			return a == null ? code : a.name();
		}
	}

	record MonthDto(String month, LocalDate asOf, OffsetDateTime createdAt) {
	}

	record CellDto(String to, String toLabel, long count, long balance, BigDecimal countRate,
			BigDecimal balanceRate) {
	}

	record RollRowDto(String from, String fromLabel, long count, long balance, List<CellDto> cells,
			BigDecimal worsenRate, BigDecimal cureRate) {
	}

	record RollRateDto(String month, String previousMonth, boolean previousAvailable, boolean currentClosed,
			LocalDate asOf, List<String> columns, List<RollRowDto> rows) {

		static RollRateDto of(ReportService.RollRateView v) {
			List<RollRowDto> rows = v.result().rows().stream()
					.map(r -> new RollRowDto(r.from() == null ? "NEW" : r.from().name(),
							r.from() == null ? "신규" : r.from().label(), r.count(), r.balance(),
							r.cells().stream().map(c -> new CellDto(c.to().name(), c.to().label(), c.count(),
									c.balance(), c.countRate(), c.balanceRate())).toList(),
							r.worsenRate(), r.cureRate()))
					.toList();
			return new RollRateDto(v.month().toString(), v.previousMonth().toString(), v.previousAvailable(),
					v.currentClosed(), v.asOf(), v.result().columns().stream().map(Bucket::name).toList(), rows);
		}
	}

	record ProvisionRowDto(String bucket, String label, long count, long claim, BigDecimal rate, long provision) {
	}

	record ProvisionDto(String month, LocalDate asOf, boolean closed, long count, long claim, long provision,
			List<ProvisionRowDto> rows) {

		static ProvisionDto of(ReportService.ProvisionView v) {
			return new ProvisionDto(v.month() == null ? null : v.month().toString(), v.asOf(), v.closed(),
					v.result().count(), v.result().claim(), v.result().provision(),
					v.result().rows().stream().map(r -> new ProvisionRowDto(r.bucket().name(), r.bucket().label(),
							r.count(), r.claim(), r.rate(), r.provision())).toList());
		}
	}

	// ------------------------------------------------------------------

	private final ReportService reports;
	private final BusinessDates businessDates;

	ReportController(ReportService reports, BusinessDates businessDates) {
		this.reports = reports;
		this.businessDates = businessDates;
	}

	/** 일보. date가 없으면 현재 업무일자(미리보기) */
	@GetMapping("/daily")
	DailyDto daily(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate date) {
		return DailyDto.of(reports.daily(date == null ? businessDates.today() : date));
	}

	@GetMapping("/journal")
	JournalDto journal(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate date) {
		return JournalDto.of(reports.journal(date == null ? businessDates.today() : date));
	}

	@GetMapping("/months")
	List<MonthDto> months() {
		return reports.months().stream()
				.map(m -> new MonthDto(YearMonth.from(m.month()).toString(), m.asOf(), m.createdAt())).toList();
	}

	/** 전이율. month가 없으면 현재 업무일자의 달 (월말 전이면 현재 잔액 미리보기) */
	@GetMapping("/roll-rate")
	RollRateDto rollRate(@RequestParam(required = false) @Nullable String month) {
		YearMonth m = month == null || month.isBlank() ? YearMonth.from(businessDates.today()) : parse(month);
		return RollRateDto.of(reports.rollRate(m));
	}

	/** 대손충당금 기초 집계. month가 없으면 현재 */
	@GetMapping("/provision")
	ProvisionDto provision(@RequestParam(required = false) @Nullable String month) {
		return ProvisionDto.of(reports.provision(month == null || month.isBlank() ? null : parse(month)));
	}

	static YearMonth parse(String month) {
		try {
			return YearMonth.parse(month.trim());
		} catch (DateTimeParseException e) {
			throw new BusinessException("INVALID_MONTH", "월은 YYYY-MM 형식입니다: " + month);
		}
	}
}
