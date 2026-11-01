package dev.arche.servicing.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.common.BusinessException;
import dev.arche.common.Money;
import dev.arche.servicing.AccountRef;
import dev.arche.staff.CurrentStaff;
import dev.arche.staff.StaffDirectory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.util.List;
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
 * 채권 생애주기 API (R39~R44, ADR 0005): 상각 후보·확정, 매각·환매·양수인 이전, 수기 조정·상계·상태 되돌리기 결재.
 */
@RestController
@RequestMapping("/api")
class LifecycleController {

	// ------------------------------------------------------------------ DTO

	record ReasonRequest(@NotBlank String reason) {
	}

	record SaleRequest(@NotBlank String buyer, @Positive long price, @NotNull LocalDate noticeSentOn,
			@NotBlank String reason) {
	}

	record TransferRequest(@Positive long amount, String memo) {
	}

	record SaleDto(long id, String buyer, long salePrice, LocalDate soldOn, LocalDate noticeSentOn, String reason,
			String status, LocalDate repurchaseDeadline, LocalDate repurchasedOn, Long repurchasePrice,
			String repurchaseReason, String createdBy) {
		static SaleDto of(LoanSale s) {
			return new SaleDto(s.getId(), s.buyer(), s.salePrice(), s.soldOn(), s.noticeSentOn(), s.reason(),
					s.status().name(), s.repurchaseDeadline(), s.repurchasedOn(), s.repurchasePrice(),
					s.repurchaseReason(), s.getCreatedBy());
		}
	}

	record SetoffEventDto(long eventId, String direction, LocalDate date, long amount, Long counterpartAccountId,
			String counterpartAccountNo, String source, String status, String reason) {
	}

	record RequestDto(long id, String requestNo, String type, long accountId, String accountNo, Long sourceAccountId,
			String sourceAccountNo, String setoffSource, long principalDown, long normalDown, long lateDown,
			long costDelta, long preAcqDown, long amount, Long targetEventId, String reason, String status,
			String requestedBy, LocalDate requestedOn, String decidedBy, LocalDate decidedOn, Long appliedEventId,
			LocalDate consumedOn) {
	}

	record Lifecycle(LocalDate businessDate, String status, LocalDate acceleratedOn, LocalDate chargedOffOn,
			LocalDate soldOn, long transferPending, long claim, long refundPending, long suspenseBalance,
			boolean chargeOffCandidate, String chargeOffBlocker, LocalDate lastPaymentOn, List<SaleDto> sales,
			List<SetoffEventDto> setoffs, List<RequestDto> requests) {
	}

	record AdjustmentRequestBody(@NotNull AdjustmentRequest.Type type, @NotNull Long accountId,
			String sourceAccountNo, String setoffSource, Long principalDown, Long normalDown, Long lateDown,
			Long costDelta, Long preAcqDown, Long amount, Long targetEventId, @NotBlank String reason) {
	}

	/** 빈 금액 = 0 */
	private static long z(Long v) {
		return v == null ? 0 : v;
	}

	record DecisionRequest(boolean approve) {
	}

	// ------------------------------------------------------------------

	private final LoanAccountRepository accounts;
	private final AccountEventRepository events;
	private final LoanSaleRepository sales;
	private final AdjustmentRequestRepository requests;
	private final LifecycleService service;
	private final StaffDirectory staff;
	private final BusinessDates businessDates;

	LifecycleController(LoanAccountRepository accounts, AccountEventRepository events, LoanSaleRepository sales,
			AdjustmentRequestRepository requests, LifecycleService service, StaffDirectory staff,
			BusinessDates businessDates) {
		this.accounts = accounts;
		this.events = events;
		this.sales = sales;
		this.requests = requests;
		this.service = service;
		this.staff = staff;
		this.businessDates = businessDates;
	}

	// ------------------------------------------------------------------ 조회

	@GetMapping("/loans/{id}/lifecycle")
	@PreAuthorize("hasAuthority('loan.read')")
	@Transactional(readOnly = true)
	Lifecycle lifecycle(@PathVariable long id) {
		LoanAccount a = visible(id);
		LocalDate today = businessDates.today();
		LocalDate last = service.lastPaymentOn(id, today);
		String blocker = LifecycleService.chargeOffBlocker(a, last, today).orElse(null);
		List<SetoffEventDto> setoffs = events.findByAccountIdOrderById(id).stream()
				.filter(e -> e.type() == AccountEvent.Type.SETOFF_IN || e.type() == AccountEvent.Type.SETOFF_OUT)
				.map(this::setoff).toList();
		return new Lifecycle(today, a.status().name(), a.acceleratedOn(), a.chargedOffOn(), a.soldOn(),
				a.transferPending(), LifecycleService.claim(a), a.refundPending(), a.suspenseBalance(), blocker == null,
				blocker, last, sales.findByAccountIdOrderByIdDesc(id).stream().map(SaleDto::of).toList(), setoffs,
				requests.findByAccountIdOrderByIdDesc(id).stream().map(this::dto).toList());
	}

	private SetoffEventDto setoff(AccountEvent e) {
		AccountEvent other = e.linkedEventId() == null ? null : events.findById(e.linkedEventId()).orElse(null);
		Long otherAccount = other == null ? null : other.accountId();
		String otherNo = otherAccount == null ? null : accounts.findById(otherAccount).map(LoanAccount::accountNo)
				.orElse(null);
		String source = e.type() == AccountEvent.Type.SETOFF_OUT ? e.setoffSource()
				: other == null ? null : other.setoffSource();
		return new SetoffEventDto(e.getId(), e.type() == AccountEvent.Type.SETOFF_IN ? "IN" : "OUT", e.eventDate(),
				e.amount(), otherAccount, otherNo, source, e.status(), e.reason());
	}

	@GetMapping("/loans/charge-off-candidates")
	@PreAuthorize("hasAuthority('loan.chargeoff.confirm')")
	List<LifecycleService.Candidate> candidates() {
		return service.candidates();
	}

	// ------------------------------------------------------------------ 상각·매각·환매 (본부장, 9.1)

	@PostMapping("/loans/{id}/charge-off")
	@PreAuthorize("hasAuthority('loan.chargeoff.confirm')")
	AccountRef chargeOff(@PathVariable long id, @Valid @RequestBody ReasonRequest body) {
		visible(id);
		return service.chargeOff(id, body.reason());
	}

	@PostMapping("/loans/{id}/sale")
	@PreAuthorize("hasAuthority('loan.chargeoff.confirm')")
	AccountRef sell(@PathVariable long id, @Valid @RequestBody SaleRequest body) {
		visible(id);
		return service.sell(id, new LifecycleService.SaleCommand(body.buyer(), Money.won(body.price()),
				body.noticeSentOn(), body.reason()));
	}

	@PostMapping("/loans/{id}/repurchase")
	@PreAuthorize("hasAuthority('loan.chargeoff.confirm')")
	AccountRef repurchase(@PathVariable long id, @Valid @RequestBody ReasonRequest body) {
		visible(id);
		return service.repurchase(id, body.reason());
	}

	@PostMapping("/loans/{id}/buyer-transfers")
	@PreAuthorize("hasAuthority('loan.chargeoff.confirm')")
	AccountRef transfer(@PathVariable long id, @Valid @RequestBody TransferRequest body) {
		visible(id);
		return service.transferToBuyer(id, Money.won(body.amount()), body.memo());
	}

	// ------------------------------------------------------------------ 결재 (R42~R44)

	@PostMapping("/adjustment-requests")
	@PreAuthorize("hasAuthority('loan.adjust.request')")
	RequestDto request(@Valid @RequestBody AdjustmentRequestBody body) {
		visible(body.accountId());
		Long sourceId = null;
		if (body.type() == AdjustmentRequest.Type.SETOFF) {
			if (body.sourceAccountNo() == null || body.sourceAccountNo().isBlank()) {
				throw new BusinessException("SETOFF_INVALID", "상계 원천 계약번호를 입력하세요");
			}
			LoanAccount source = accounts.findByAccountNo(body.sourceAccountNo().trim())
					.orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "원천 계약이 없습니다"));
			visible(source.getId());
			sourceId = source.getId();
		}
		return dto(service.request(new LifecycleService.RequestCommand(body.type(), body.accountId(), sourceId,
				body.setoffSource(), z(body.principalDown()), z(body.normalDown()), z(body.lateDown()),
				z(body.costDelta()), z(body.preAcqDown()), z(body.amount()), body.targetEventId(), body.reason())));
	}

	@GetMapping("/adjustment-requests")
	@PreAuthorize("hasAnyAuthority('loan.adjust.request', 'loan.adjust.approve')")
	@Transactional(readOnly = true)
	List<RequestDto> requests(@RequestParam(defaultValue = "REQUESTED") String status) {
		List<AdjustmentRequest> rows = "ALL".equals(status) ? requests.findTop200ByOrderByIdDesc()
				: requests.findTop200ByStatusOrderByIdDesc(AdjustmentRequest.Status.valueOf(status));
		var scope = staff.loanScope();
		return rows.stream().filter(r -> accounts.findById(r.accountId())
				.map(a -> scope.allows(a.assignedStaffId())).orElse(false)).map(this::dto).toList();
	}

	@PostMapping("/adjustment-requests/{requestId}/decision")
	@PreAuthorize("hasAuthority('loan.adjust.approve')")
	RequestDto decide(@PathVariable long requestId, @RequestBody DecisionRequest body) {
		return dto(service.decide(requestId, body.approve()));
	}

	// ------------------------------------------------------------------

	private RequestDto dto(AdjustmentRequest r) {
		return new RequestDto(r.getId(), r.requestNo(), r.type().name(), r.accountId(), accountNo(r.accountId()),
				r.sourceAccountId(), r.sourceAccountId() == null ? null : accountNo(r.sourceAccountId()),
				r.setoffSource(), r.principalDown(), r.normalDown(), r.lateDown(), r.costDelta(), r.preAcqDown(),
				r.amount(), r.targetEventId(), r.reason(), r.status().name(), loginId(r.requestedBy()),
				r.requestedOn(), r.decidedBy() == null ? null : loginId(r.decidedBy()), r.decidedOn(),
				r.appliedEventId(), r.consumedOn());
	}

	private String accountNo(long id) {
		return accounts.findById(id).map(LoanAccount::accountNo).orElse(null);
	}

	private String loginId(long staffId) {
		return staff.byId(staffId).map(CurrentStaff::loginId).orElse(String.valueOf(staffId));
	}

	private LoanAccount visible(long id) {
		LoanAccount a = accounts.findById(id)
				.orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "계약이 없습니다"));
		if (!staff.loanScope().allows(a.assignedStaffId())) {
			throw new BusinessException("FORBIDDEN", "담당 범위 밖의 계약입니다");
		}
		return a;
	}
}
