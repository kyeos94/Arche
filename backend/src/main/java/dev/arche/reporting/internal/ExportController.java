package dev.arche.reporting.internal;

import dev.arche.servicing.LoanFilter;

import dev.arche.calendar.BusinessDates;
import dev.arche.common.BusinessException;
import dev.arche.customer.CustomerView;
import dev.arche.customer.Customers;
import dev.arche.servicing.ServicingReports;
import dev.arche.staff.AccessLogger;
import dev.arche.staff.DataScope;
import dev.arche.staff.StaffDirectory;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 엑셀 다운로드 (guide 13.1, 정책서 9.1 "엑셀 다운로드", R68 ②). 사유 필수, 접근 기록, 개인정보 마스킹.
 * POST인 이유: 사유를 URL에 남기지 않고 CSRF 보호를 받기 위해 (ADR 0009).
 */
@RestController
@RequestMapping("/api/exports")
class ExportController {

	static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
	static final String ACTION = "EXCEL_DOWNLOAD";

	/** 계약 목록과 같은 검색 조건 */
	record LoanExportRequest(@Nullable LoanFilter filter, @Nullable String status, @Nullable String q,
			@NotBlank @Size(min = 2, max = 200) String reason) {

		/** 예전 요청(status, q)도 받는다 */
		LoanFilter effective() {
			if (filter != null) {
				return filter;
			}
			return new LoanFilter(status, null, null, false, null, null, null, null, null, null, null, null, null,
					null, q == null || q.isBlank() ? null : "AUTO", q);
		}
	}

	enum ReportKind {
		DAILY, JOURNAL, ROLL_RATE, PROVISION
	}

	record ReportExportRequest(@NotNull ReportKind kind, @Nullable LocalDate date, @Nullable String month,
			@NotBlank @Size(min = 2, max = 200) String reason) {
	}

	private final ServicingReports servicing;
	private final Customers customers;
	private final ReportService reports;
	private final StaffDirectory staff;
	private final AccessLogger accessLog;
	private final BusinessDates businessDates;

	ExportController(ServicingReports servicing, Customers customers, ReportService reports, StaffDirectory staff,
			AccessLogger accessLog, BusinessDates businessDates) {
		this.servicing = servicing;
		this.customers = customers;
		this.reports = reports;
		this.staff = staff;
		this.accessLog = accessLog;
		this.businessDates = businessDates;
	}

	/** 계약 목록: 계약 조회 권한도 필요하고 데이터 범위(담당 건 / 팀 전체)를 그대로 적용 (R68 ②) */
	@PostMapping("/loans")
	@PreAuthorize("hasAuthority('excel.download') and hasAuthority('loan.read')")
	void loans(@Valid @RequestBody LoanExportRequest request, HttpServletResponse response) throws IOException {
		requireReason(request.reason());
		DataScope scope = staff.loanScope();
		LoanFilter filter = request.effective();
		// 검색어 원문은 주민등록번호일 수 있어 남기지 않는다 (LoanFilter.toString도 검색어를 빼고 쓴다)
		String target = "status=" + (filter.status() == null || filter.status().isBlank() ? "ALL" : filter.status())
				+ (filter.keyword() == null || filter.keyword().isBlank() ? "" : ",keyword");
		accessLog.log(ACTION, "LOAN_LIST", target.length() > 50 ? target.substring(0, 50) : target,
				request.reason().trim());
		start(response, "loans-" + businessDates.today() + ".xlsx");
		Map<Long, CustomerView> cache = new HashMap<>();
		try (XlsxWriter w = new XlsxWriter()) {
			w.sheet("계약 목록").header("계약번호", "고객명", "주민등록번호", "휴대폰", "상품", "상태", "원금 잔액", "완제 금액", "연체일수",
					"다음 납입일", "담당자 ID");
			servicing.forEachLoan(filter, scope, row -> {
				CustomerView c = cache.computeIfAbsent(row.customerId(), customers::get);
				w.row(row.accountNo(), maskName(c.name()), c.residentNoMasked(), c.phoneMasked(), row.productCode(),
						ReportLabels.status(row.status()), row.principalBalance(), row.payoffAmount(),
						row.delinquencyDays(), row.nextDueDate(), row.assignedStaffId());
			});
			w.write(response.getOutputStream());
		}
	}

	@PostMapping("/reports")
	@PreAuthorize("hasAuthority('excel.download') and hasAuthority('report.read')")
	void report(@Valid @RequestBody ReportExportRequest request, HttpServletResponse response) throws IOException {
		requireReason(request.reason());
		LocalDate date = request.date() == null ? businessDates.today() : request.date();
		String month = request.month() == null || request.month().isBlank() ? null : request.month().trim();
		// 응답을 쓰기 전에 계산해 오류를 JSON으로 돌려줄 수 있게 한다
		ReportExports.Writer writer = switch (request.kind()) {
			case DAILY -> ReportExports.daily(ReportController.DailyDto.of(reports.daily(date)));
			case JOURNAL -> ReportExports.journal(ReportController.JournalDto.of(reports.journal(date)));
			case ROLL_RATE -> ReportExports.rollRate(ReportController.RollRateDto.of(reports.rollRate(
					month == null ? java.time.YearMonth.from(businessDates.today()) : ReportController.parse(month))));
			case PROVISION -> ReportExports.provision(ReportController.ProvisionDto
					.of(reports.provision(month == null ? null : ReportController.parse(month))));
		};
		String target = request.kind() + ":" + (request.kind() == ReportKind.DAILY || request.kind() == ReportKind.JOURNAL
				? date.toString() : month == null ? "current" : month);
		accessLog.log(ACTION, "REPORT", target, request.reason().trim());
		start(response, request.kind().name().toLowerCase().replace('_', '-') + "-"
				+ target.substring(target.indexOf(':') + 1) + ".xlsx");
		try (XlsxWriter w = new XlsxWriter()) {
			writer.write(w);
			w.write(response.getOutputStream());
		}
	}

	private static void start(HttpServletResponse response, String filename) {
		response.setContentType(XLSX);
		response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
				ContentDisposition.attachment().filename(filename).build().toString());
	}

	/** 홍길동 → 홍*동, 김철 → 김*, 남궁민수 → 남**수 */
	static String maskName(@Nullable String name) {
		if (name == null || name.isBlank()) {
			return "";
		}
		int n = name.codePointCount(0, name.length());
		if (n == 1) {
			return "*";
		}
		int[] cps = name.codePoints().toArray();
		StringBuilder sb = new StringBuilder().appendCodePoint(cps[0]);
		if (n == 2) {
			return sb.append('*').toString();
		}
		sb.append("*".repeat(n - 2));
		return sb.appendCodePoint(cps[n - 1]).toString();
	}

	static void requireReason(String reason) {
		if (reason == null || reason.trim().length() < 2) {
			throw new BusinessException("REASON_REQUIRED", "다운로드 사유를 입력하세요");
		}
	}
}
