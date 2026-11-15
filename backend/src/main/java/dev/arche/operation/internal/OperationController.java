package dev.arche.operation.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.servicing.AccountRef;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/operation")
class OperationController {

	record BusinessDateDto(LocalDate businessDate, OffsetDateTime lastClosedAt) {
	}

	record ClosingDto(LocalDate businessDate, OffsetDateTime closedAt, String closedBy, int paymentCount,
			long paymentTotal, long correctionTotal, long unmatchedTotal, int accountsJudged, int newNotices,
			int accelerations, int mismatches, int judgeFailures, LocalDate nextBusinessDate) {

		static ClosingDto of(DailyClosing c) {
			return new ClosingDto(c.businessDate(), c.closedAt(), c.closedBy(), c.paymentCount(), c.paymentTotal(),
					c.correctionTotal(), c.unmatchedTotal(), c.accountsJudged(), c.newNotices(), c.accelerations(),
					c.mismatches(), c.judgeFailures(), c.businessDate().plusDays(1));
		}
	}

	/** 일마감 판정 실패 계약 (R76). kind: BUSINESS(업무 규칙 위반)·SYSTEM(그 밖의 오류) */
	record FailureDto(long id, LocalDate businessDate, long accountId, String accountNo, String kind, String code,
			String message, OffsetDateTime recordedAt, boolean resolved, OffsetDateTime resolvedAt,
			String resolvedBy) {

		static FailureDto of(ClosingFailureService.FailureView v) {
			DailyClosingFailure f = v.failure();
			return new FailureDto(f.id(), f.businessDate(), f.accountId(), v.accountNo(), f.errorKind().name(),
					f.errorCode(), f.message(), f.recordedAt(), f.resolved(), f.resolvedAt(), f.resolvedBy());
		}
	}

	record RejudgeDto(long accountId, String accountNo, String status) {
	}

	private final DailyClosingService closing;
	private final ClosingFailureService failures;
	private final BusinessDates businessDates;

	OperationController(DailyClosingService closing, ClosingFailureService failures, BusinessDates businessDates) {
		this.closing = closing;
		this.failures = failures;
		this.businessDates = businessDates;
	}

	@GetMapping("/business-date")
	BusinessDateDto businessDate() {
		List<DailyClosing> recent = closing.recent();
		return new BusinessDateDto(businessDates.today(), recent.isEmpty() ? null : recent.getFirst().closedAt());
	}

	/** 수동 일마감 (배치 관리 = 시스템관리자) */
	@PostMapping("/closings")
	@PreAuthorize("hasAuthority('admin.manage')")
	ClosingDto close() {
		return ClosingDto.of(closing.close());
	}

	@GetMapping("/closings")
	@PreAuthorize("hasAuthority('admin.manage') or hasAuthority('excel.download')")
	List<ClosingDto> closings() {
		return closing.recent().stream().map(ClosingDto::of).toList();
	}

	/** 마감일의 판정 실패 계약 목록 (R76). 일마감 이력과 같은 권한 */
	@GetMapping("/closings/{businessDate}/failures")
	@PreAuthorize("hasAuthority('admin.manage') or hasAuthority('excel.download')")
	List<FailureDto> failures(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
		return failures.ofDate(businessDate).stream().map(FailureDto::of).toList();
	}

	/** 재판정 (R76): 실패 계약 하나를 현재 업무일자까지 다시 재생 (9.1 "직원·권한·코드·배치 관리" = 시스템관리자) */
	@PostMapping("/accounts/{accountId}/rejudge")
	@PreAuthorize("hasAuthority('admin.manage')")
	RejudgeDto rejudge(@PathVariable long accountId) {
		AccountRef ref = failures.rejudge(accountId);
		return new RejudgeDto(ref.id(), ref.accountNo(), ref.status());
	}
}
