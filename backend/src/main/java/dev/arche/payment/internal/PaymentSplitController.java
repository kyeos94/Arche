package dev.arche.payment.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.common.BusinessException;
import dev.arche.common.Money;
import dev.arche.customer.CustomerView;
import dev.arche.customer.Customers;
import dev.arche.payment.Payments;
import dev.arche.payment.internal.split.AllocationStrategy;
import dev.arche.servicing.AccountRef;
import dev.arche.servicing.LoanAccounts;
import dev.arche.staff.StaffDirectory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.LinkedHashMap;
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
 * 한 고객의 여러 계약 일괄 입금 배분 (R47·R48)과 대위변제 기록 조회 (R49).
 */
@RestController
@RequestMapping("/api")
class PaymentSplitController {

	record TargetDto(long accountId, String accountNo, String productCode, String status, LocalDate disbursedOn,
			LocalDate delinquentSince, LocalDate nextDueDate, long overdue, long nextDue, long payoff) {
		static TargetDto of(LoanAccounts.DueSnapshot s) {
			return new TargetDto(s.accountId(), s.accountNo(), s.productCode(), s.status(), s.disbursedOn(),
					s.delinquentSince(), s.nextDueDate(), s.overdue().won(), s.nextDue().won(), s.payoff().won());
		}
	}

	record Targets(long customerId, String customerNo, String customerName, LocalDate businessDate,
			List<TargetDto> accounts) {
	}

	record ManualPart(@NotNull Long accountId, long amount) {
	}

	record PreviewRequest(@NotNull Long customerId, AllocationStrategy.Method method, long amount,
			List<ManualPart> manual) {
	}

	record PartDto(long accountId, String accountNo, long amount) {
	}

	record SplitRequest(@NotNull Long customerId, AllocationStrategy.Method method, List<ManualPart> manual,
			Long unmatchedId, LocalDate bankDate, Long amount, String depositorName, String bankTxId,
			Payments.Channel channel, PayerType payerType, String payerName, String payerRef) {

		@Override
		public String toString() {
			return "SplitRequest[customerId=" + customerId + ", method=" + method + ", amount=" + amount + "]";
		}
	}

	record SplitResponse(Payments.PaymentView parent, List<Payments.PaymentView> parts) {
	}

	record ClaimDto(long id, long paymentId, long accountId, String payerType, String payerNameMasked,
			String payerRef, LocalDate valueDate, long amount, String status, LocalDate recordedOn,
			LocalDate cancelledOn, String cancelReason) {
		static ClaimDto of(SubrogationClaim c) {
			return new ClaimDto(c.getId(), c.paymentId(), c.accountId(), c.payerType().name(),
					Payer.mask(c.payerName()), c.payerRef(), c.valueDate(), c.amount().won(), c.status().name(),
					c.recordedOn(), c.cancelledOn(), c.cancelReason());
		}
	}

	private final PaymentService service;
	private final SubrogationClaimRepository claims;
	private final LoanAccounts accounts;
	private final Customers customers;
	private final StaffDirectory staff;
	private final BusinessDates businessDates;

	PaymentSplitController(PaymentService service, SubrogationClaimRepository claims, LoanAccounts accounts,
			Customers customers, StaffDirectory staff, BusinessDates businessDates) {
		this.service = service;
		this.claims = claims;
		this.accounts = accounts;
		this.customers = customers;
		this.staff = staff;
		this.businessDates = businessDates;
	}

	/** 배분 대상 계약. customerId 또는 그 고객의 계약번호 하나로 찾는다 */
	@GetMapping("/payment-splits/targets")
	@PreAuthorize("hasAuthority('payment.register')")
	@Transactional(readOnly = true)
	Targets targets(@RequestParam(required = false) Long customerId, @RequestParam(required = false) String accountNo) {
		long cid;
		if (customerId != null) {
			cid = customerId;
		} else if (accountNo != null && !accountNo.isBlank()) {
			AccountRef ref = accounts.findByAccountNo(accountNo.trim())
					.orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "계약번호가 없습니다: " + accountNo));
			cid = accounts.messagingView(ref.id()).customerId();
		} else {
			throw new BusinessException("CUSTOMER_REQUIRED", "고객 또는 계약번호를 지정하세요");
		}
		CustomerView c = customers.get(cid);
		return new Targets(cid, c.customerNo(), c.name(), businessDates.today(),
				service.splitTargets(cid).stream().map(TargetDto::of).toList());
	}

	@PostMapping("/payment-splits/preview")
	@PreAuthorize("hasAuthority('payment.register')")
	@Transactional(readOnly = true)
	List<PartDto> preview(@Valid @RequestBody PreviewRequest body) {
		if (body.amount() <= 0) {
			throw new BusinessException("AMOUNT_INVALID", "입금액은 0보다 커야 합니다");
		}
		Map<Long, String> numbers = new LinkedHashMap<>();
		service.splitTargets(body.customerId()).forEach(t -> numbers.put(t.accountId(), t.accountNo()));
		return service.previewSplit(body.customerId(), method(body.method()), Money.won(body.amount()),
				manual(body.manual())).stream()
				.map(p -> new PartDto(p.accountId(), numbers.get(p.accountId()), p.amount().won())).toList();
	}

	@PostMapping("/payment-splits")
	@PreAuthorize("hasAuthority('payment.register')")
	SplitResponse split(@Valid @RequestBody SplitRequest body) {
		Payer payer = Payer.of(body.payerType(), body.payerName(), body.payerRef());
		Payments.Deposit deposit = null;
		if (body.unmatchedId() == null) {
			if (body.bankDate() == null || body.amount() == null) {
				throw new BusinessException("SPLIT_SOURCE_REQUIRED", "입금일과 입금액을 입력하세요");
			}
			String txId = body.bankTxId() == null || body.bankTxId().isBlank() ? PaymentService.manualTxId()
					: body.bankTxId();
			deposit = new Payments.Deposit(null, body.channel() == null ? Payments.Channel.BANK_TRANSFER
					: body.channel(), body.bankDate(), Money.won(body.amount()), body.depositorName(), txId);
		}
		PaymentService.SplitResult r = service.split(new PaymentService.SplitCommand(body.customerId(),
				method(body.method()), manual(body.manual()), body.unmatchedId(), deposit, payer));
		return new SplitResponse(r.parent(), r.parts());
	}

	@GetMapping("/subrogations")
	@PreAuthorize("hasAuthority('loan.read')")
	@Transactional(readOnly = true)
	List<ClaimDto> subrogations(@RequestParam long accountId) {
		AccountRef a = accounts.get(accountId);
		if (!staff.loanScope().allows(a.assignedStaffId())) {
			throw new BusinessException("FORBIDDEN", "담당 범위 밖의 계약입니다");
		}
		return claims.findByAccountIdOrderByIdDesc(accountId).stream().map(ClaimDto::of).toList();
	}

	@GetMapping("/payment-splits/{parentPaymentId}")
	@PreAuthorize("hasAuthority('payment.register') or hasAuthority('loan.read')")
	@Transactional(readOnly = true)
	SplitResponse detail(@PathVariable long parentPaymentId) {
		Payment parent = service.payment(parentPaymentId);
		if (parent.splitMethod() == null) {
			throw new BusinessException("NOT_SPLIT", "일괄 배분된 입금이 아닙니다");
		}
		return new SplitResponse(parent.view(), service.children(parentPaymentId));
	}

	private static AllocationStrategy.Method method(AllocationStrategy.Method m) {
		return m == null ? AllocationStrategy.Method.OLDEST_DELINQUENCY_FIRST : m;
	}

	private static Map<Long, Money> manual(List<ManualPart> parts) {
		Map<Long, Money> m = new LinkedHashMap<>();
		if (parts != null) {
			for (ManualPart p : parts) {
				m.merge(p.accountId(), Money.won(p.amount()), Money::plus);
			}
		}
		return m;
	}
}
