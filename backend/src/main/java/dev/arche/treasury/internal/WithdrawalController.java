package dev.arche.treasury.internal;

import dev.arche.payment.UnmatchedRefunds;
import dev.arche.servicing.RefundPayouts;
import dev.arche.staff.StaffDirectory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 출금 작성·승인 API (guide 6.4, 13.3 "출금 승인"). 작성은 출납, 승인은 채권팀장·본부장 (정책서 9.1, R62~R64).
 */
@RestController
@RequestMapping("/api/withdrawals")
class WithdrawalController {

	record CreateRequest(@NotNull Withdrawal.Type type, Long accountId, Long unmatchedId, @Positive long amount,
			@NotBlank @Size(max = 40) String bankName, @NotBlank @Pattern(regexp = "[0-9-]{6,20}") String accountNo,
			@NotBlank @Size(max = 50) String holderName, @NotBlank @Size(max = 200) String reason) {

		@Override
		public String toString() {
			return "CreateRequest[" + type + ", " + amount + "]";
		}
	}

	record RejectRequest(@NotBlank @Size(max = 200) String reason) {
	}

	record WithdrawalDto(long id, String withdrawalNo, String type, Long accountId, Long unmatchedId, long amount,
			String bankName, String payeeAccountMasked, String holderName, String reason, String status,
			boolean twoStep, LocalDate requestedOn, String requestedBy, String firstApprovedBy,
			OffsetDateTime firstApprovedAt, String finalApprovedBy, OffsetDateTime finalApprovedAt,
			String rejectedBy, String rejectReason, String idempotencyKey, String providerTxId, String resultCode,
			String resultMessage, OffsetDateTime sentAt, OffsetDateTime completedAt, int inquiryCount,
			String nextStep) {
	}

	record Candidates(List<UnmatchedRefunds.UnmatchedInfo> unmatched, List<RefundPayouts.Refundable> refunds) {
	}

	private final WithdrawalService service;
	private final WithdrawalRepository withdrawals;
	private final UnmatchedRefunds unmatched;
	private final RefundPayouts refunds;
	private final StaffDirectory staff;

	WithdrawalController(WithdrawalService service, WithdrawalRepository withdrawals, UnmatchedRefunds unmatched,
			RefundPayouts refunds, StaffDirectory staff) {
		this.service = service;
		this.withdrawals = withdrawals;
		this.unmatched = unmatched;
		this.refunds = refunds;
		this.staff = staff;
	}

	@GetMapping
	@PreAuthorize("hasAnyAuthority('withdrawal.create', 'withdrawal.approve.small', 'withdrawal.approve.large')")
	@Transactional(readOnly = true)
	List<WithdrawalDto> list(@RequestParam(defaultValue = "") String status) {
		List<Withdrawal> list = status.isBlank() ? withdrawals.findTop200ByOrderByIdDesc()
				: withdrawals.findByStatusInOrderByIdDesc(
						java.util.Arrays.stream(status.split(",")).map(Withdrawal.Status::valueOf).toList());
		return list.stream().map(this::dto).toList();
	}

	@GetMapping("/candidates")
	@PreAuthorize("hasAuthority('withdrawal.create')")
	Candidates candidates() {
		return new Candidates(unmatched.open(), refunds.withRefundPending());
	}

	@PostMapping
	@PreAuthorize("hasAuthority('withdrawal.create')")
	WithdrawalDto create(@Valid @RequestBody CreateRequest r) {
		Withdrawal w = service.create(new WithdrawalService.CreateCommand(r.type(), r.accountId(), r.unmatchedId(),
				r.amount(), r.bankName().strip(), r.accountNo(), r.holderName(), r.reason()));
		return dto(w);
	}

	@PostMapping("/{id}/approve")
	@PreAuthorize("hasAnyAuthority('withdrawal.approve.small', 'withdrawal.approve.large')")
	WithdrawalDto approve(@PathVariable long id) {
		return dto(service.approve(id));
	}

	@PostMapping("/{id}/reject")
	@PreAuthorize("hasAnyAuthority('withdrawal.approve.small', 'withdrawal.approve.large')")
	WithdrawalDto reject(@PathVariable long id, @Valid @RequestBody RejectRequest r) {
		return dto(service.reject(id, r.reason()));
	}

	@PostMapping("/{id}/cancel")
	@PreAuthorize("hasAuthority('withdrawal.create')")
	WithdrawalDto cancel(@PathVariable long id) {
		return dto(service.cancel(id));
	}

	/** 응답 불명 건 결과 조회 (평소에는 1분마다 자동) */
	@PostMapping("/{id}/inquire")
	@PreAuthorize("hasAnyAuthority('withdrawal.create', 'withdrawal.approve.small', 'withdrawal.approve.large')")
	WithdrawalDto inquire(@PathVariable long id) {
		return dto(service.inquire(id));
	}

	private WithdrawalDto dto(Withdrawal w) {
		Map<Long, String> names = new java.util.HashMap<>();
		return new WithdrawalDto(w.getId(), w.withdrawalNo(), w.type().name(), w.accountId(), w.unmatchedId(),
				w.amount().won(), w.bankName(), w.payeeAccountMasked(), w.holderName(), w.reason(), w.status().name(),
				w.twoStep(), w.requestedOn(), name(names, w.requestedBy()), name(names, w.firstApprovedBy()),
				w.firstApprovedAt(), name(names, w.finalApprovedBy()), w.finalApprovedAt(),
				name(names, w.rejectedBy()), w.rejectReason(), w.idempotencyKey(), w.providerTxId(), w.resultCode(),
				w.resultMessage(), w.sentAt(), w.completedAt(), w.inquiryCount(), nextStep(w));
	}

	private String name(Map<Long, String> cache, Long staffId) {
		if (staffId == null) {
			return null;
		}
		return cache.computeIfAbsent(staffId,
				id -> staff.byId(id).map(s -> s.name() + " (" + s.loginId() + ")").orElse("#" + id));
	}

	/** 화면 안내: 지금 누가 무엇을 해야 하는지 */
	private static String nextStep(Withdrawal w) {
		return switch (w.status()) {
			case REQUESTED -> w.twoStep() ? "채권팀장 1단계 승인" : "채권팀장 또는 본부장 승인";
			case FIRST_APPROVED -> "본부장 2단계 승인";
			case SENDING -> "송금 중";
			case UNKNOWN -> "결과 조회 필요";
			default -> null;
		};
	}
}
