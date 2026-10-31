package dev.arche.payment.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.common.BusinessException;
import dev.arche.common.Money;
import dev.arche.payment.Payments;
import dev.arche.servicing.AccountRef;
import dev.arche.servicing.LoanAccounts;
import dev.arche.staff.StaffDirectory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
 * 입금 등록·미확인 입금 매칭·취소·정정 API. 화면의 "입금 삭제"는 내부적으로 취소 거래 + 재계산이다 (정책서 10장).
 */
@RestController
@RequestMapping("/api")
class PaymentController {

	record RegisterRequest(Long accountId, String accountNo, @NotNull LocalDate bankDate, @Positive long amount,
			String depositorName, String bankTxId, Payments.Channel channel, Boolean prepayment,
			Long prepaymentPrincipal) {
	}

	record MatchRequest(Long accountId, String accountNo) {
	}

	record CorrectionBody(@NotNull PaymentCorrection.Type type, @NotNull Long paymentId, Long targetAccountId,
			String targetAccountNo, Long correctedAmount, @NotBlank String reason) {
	}

	record ApprovalBody(boolean approve) {
	}

	record UnmatchedDto(long id, long paymentId, String status, LocalDate valueDate, LocalDate businessDate,
			long amount, String depositorName, String bankTxId, Long matchedAccountId, OffsetDateTime matchedAt) {
	}

	record CorrectionDto(long id, String correctionNo, String type, long originalPaymentId, Long newPaymentId,
			Long targetAccountId, Long correctedAmount, String reason, boolean afterClose, long requestedBy,
			Long approvedBy, String status, LocalDate requestedOn, LocalDate appliedOn) {
		static CorrectionDto of(PaymentCorrection c) {
			return new CorrectionDto(c.getId(), c.correctionNo(), c.type().name(), c.originalPaymentId(),
					c.newPaymentId(), c.targetAccountId(), c.correctedAmount(), c.reason(), c.afterClose(),
					c.requestedBy(), c.approvedBy(), c.status().name(), c.requestedOn(), c.appliedOn());
		}
	}

	record PaymentRow(long id, Long accountId, String channel, LocalDate valueDate, LocalDate businessDate,
			long amount, String depositorName, String bankTxId, String status, Long processedBy,
			boolean cancellableToday) {
	}

	private final PaymentService service;
	private final PaymentRepository payments;
	private final UnmatchedDepositRepository unmatched;
	private final PaymentCorrectionRepository corrections;
	private final LoanAccounts accounts;
	private final BusinessDates businessDates;
	private final StaffDirectory staff;

	PaymentController(PaymentService service, PaymentRepository payments, UnmatchedDepositRepository unmatched,
			PaymentCorrectionRepository corrections, LoanAccounts accounts, BusinessDates businessDates,
			StaffDirectory staff) {
		this.service = service;
		this.payments = payments;
		this.unmatched = unmatched;
		this.corrections = corrections;
		this.accounts = accounts;
		this.businessDates = businessDates;
		this.staff = staff;
	}

	@PostMapping("/payments")
	@PreAuthorize("hasAuthority('payment.register')")
	Payments.PaymentView register(@Valid @RequestBody RegisterRequest body) {
		Long accountId = resolve(body.accountId(), body.accountNo(), true);
		LoanAccounts.PrepaymentOption prepay = Boolean.TRUE.equals(body.prepayment())
				? new LoanAccounts.PrepaymentOption(
						body.prepaymentPrincipal() == null ? null : Money.won(body.prepaymentPrincipal()))
				: null;
		String txId = body.bankTxId() == null || body.bankTxId().isBlank() ? PaymentService.manualTxId()
				: body.bankTxId();
		return service.register(new Payments.Deposit(accountId,
				body.channel() == null ? Payments.Channel.BANK_TRANSFER : body.channel(), body.bankDate(),
				Money.won(body.amount()), body.depositorName(), txId), prepay);
	}

	@GetMapping("/payments")
	@PreAuthorize("hasAuthority('loan.read') or hasAuthority('payment.register')")
	@Transactional(readOnly = true)
	List<PaymentRow> list(@RequestParam(required = false) Long accountId,
			@RequestParam(required = false) String status, @RequestParam(required = false) LocalDate date) {
		LocalDate today = businessDates.today();
		List<Payment> rows = accountId != null ? payments.findByAccountIdOrderByIdDesc(accountId)
				: payments.search(status == null ? null : Payment.Status.valueOf(status), date);
		return rows.stream().map(p -> new PaymentRow(p.getId(), p.accountId(), p.channel().name(), p.valueDate(),
				p.businessDate(), p.amount().won(), p.depositorName(), p.bankTxId(), p.status().name(),
				p.processedBy(), p.businessDate().equals(today))).toList();
	}

	@GetMapping("/unmatched-deposits")
	@PreAuthorize("hasAuthority('payment.register')")
	@Transactional(readOnly = true)
	List<UnmatchedDto> unmatched(@RequestParam(defaultValue = "OPEN") String status) {
		return unmatched.findByStatusOrderByIdDesc(UnmatchedDeposit.Status.valueOf(status)).stream().map(u -> {
			Payment p = payments.findById(u.paymentId()).orElseThrow();
			return new UnmatchedDto(u.getId(), p.getId(), u.status().name(), p.valueDate(), p.businessDate(),
					p.amount().won(), p.depositorName(), p.bankTxId(), u.matchedAccountId(), u.matchedAt());
		}).toList();
	}

	@PostMapping("/unmatched-deposits/{id}/match")
	@PreAuthorize("hasAuthority('payment.register')")
	Payments.PaymentView match(@PathVariable long id, @RequestBody MatchRequest body) {
		return service.match(id, resolve(body.accountId(), body.accountNo(), false));
	}

	@PostMapping("/payment-corrections")
	@PreAuthorize("hasAuthority('payment.register') or hasAuthority('payment.cancel.closed')")
	CorrectionDto correct(@Valid @RequestBody CorrectionBody body) {
		Long target = body.type() == PaymentCorrection.Type.MOVE
				? resolve(body.targetAccountId(), body.targetAccountNo(), false)
				: null;
		return CorrectionDto.of(service.request(new PaymentService.CorrectionRequest(body.type(), body.paymentId(),
				target, body.correctedAmount(), body.reason())));
	}

	@PostMapping("/payment-corrections/{id}/approval")
	@PreAuthorize("hasAuthority('payment.cancel.closed.approve')")
	CorrectionDto approve(@PathVariable long id, @RequestBody ApprovalBody body) {
		return CorrectionDto.of(service.approve(id, body.approve()));
	}

	@GetMapping("/payment-corrections")
	@PreAuthorize("hasAuthority('payment.register') or hasAuthority('payment.cancel.closed')"
			+ " or hasAuthority('payment.cancel.closed.approve')")
	@Transactional(readOnly = true)
	List<CorrectionDto> corrections(@RequestParam(required = false) String status) {
		List<PaymentCorrection> rows = status == null ? corrections.findAllByOrderByIdDesc()
				: corrections.findByStatusOrderByIdDesc(PaymentCorrection.Status.valueOf(status));
		return rows.stream().map(CorrectionDto::of).toList();
	}

	private Long resolve(Long accountId, String accountNo, boolean optional) {
		if (accountId != null) {
			return accounts.get(accountId).id();
		}
		if (accountNo != null && !accountNo.isBlank()) {
			return accounts.findByAccountNo(accountNo.trim()).map(AccountRef::id)
					.orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "계약번호가 없습니다: " + accountNo));
		}
		if (optional) {
			return null;
		}
		throw new BusinessException("ACCOUNT_REQUIRED", "계약을 지정하세요");
	}
}
