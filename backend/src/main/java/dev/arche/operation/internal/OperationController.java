package dev.arche.operation.internal;

import dev.arche.calendar.BusinessDates;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
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
			int accelerations, int mismatches, LocalDate nextBusinessDate) {

		static ClosingDto of(DailyClosing c) {
			return new ClosingDto(c.businessDate(), c.closedAt(), c.closedBy(), c.paymentCount(), c.paymentTotal(),
					c.correctionTotal(), c.unmatchedTotal(), c.accountsJudged(), c.newNotices(), c.accelerations(),
					c.mismatches(), c.businessDate().plusDays(1));
		}
	}

	private final DailyClosingService closing;
	private final BusinessDates businessDates;

	OperationController(DailyClosingService closing, BusinessDates businessDates) {
		this.closing = closing;
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
}
