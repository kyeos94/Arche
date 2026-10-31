package dev.arche.payment.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.common.BusinessException;
import dev.arche.common.Money;
import dev.arche.common.jpa.NumberGenerator;
import dev.arche.payment.Payments;
import dev.arche.servicing.AccountRef;
import dev.arche.servicing.LoanAccounts;
import dev.arche.staff.CurrentStaff;
import dev.arche.staff.Permission;
import dev.arche.staff.Role;
import dev.arche.staff.StaffDirectory;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

interface PaymentRepository extends JpaRepository<Payment, Long> {

	Optional<Payment> findByBankTxId(String bankTxId);

	boolean existsByBankTxId(String bankTxId);

	List<Payment> findByAccountIdOrderByIdDesc(Long accountId);

	@Query("select p from Payment p where (:status is null or p.status = :status)"
			+ " and (:date is null or p.businessDate = :date) order by p.id desc")
	List<Payment> search(@Param("status") Payment.Status status, @Param("date") LocalDate date);

	@Query("select count(p), coalesce(sum(p.amount), 0) from Payment p"
			+ " where p.businessDate = :date and p.status = dev.arche.payment.internal.Payment.Status.ALLOCATED")
	List<Object[]> allocatedTotals(@Param("date") LocalDate date);
}

interface UnmatchedDepositRepository extends JpaRepository<UnmatchedDeposit, Long> {

	List<UnmatchedDeposit> findByStatusOrderByIdDesc(UnmatchedDeposit.Status status);

	Optional<UnmatchedDeposit> findByPaymentIdAndStatus(long paymentId, UnmatchedDeposit.Status status);

	@Query("select coalesce(sum(p.amount), 0) from UnmatchedDeposit u, Payment p"
			+ " where u.paymentId = p.id and u.status = dev.arche.payment.internal.UnmatchedDeposit.Status.OPEN")
	long openTotal();
}

interface PaymentCorrectionRepository extends JpaRepository<PaymentCorrection, Long> {

	List<PaymentCorrection> findByStatusOrderByIdDesc(PaymentCorrection.Status status);

	List<PaymentCorrection> findAllByOrderByIdDesc();
}

/**
 * 입금 등록·매칭·취소·정정 (정책서 4장, 10장 R26~R31).
 */
@Service
class PaymentService implements Payments {

	private final PaymentRepository payments;
	private final UnmatchedDepositRepository unmatched;
	private final PaymentCorrectionRepository corrections;
	private final LoanAccounts accounts;
	private final BusinessDates businessDates;
	private final StaffDirectory staff;
	private final NumberGenerator numbers;
	private final Clock clock;

	PaymentService(PaymentRepository payments, UnmatchedDepositRepository unmatched,
			PaymentCorrectionRepository corrections, LoanAccounts accounts, BusinessDates businessDates,
			StaffDirectory staff, NumberGenerator numbers, Clock clock) {
		this.payments = payments;
		this.unmatched = unmatched;
		this.corrections = corrections;
		this.accounts = accounts;
		this.businessDates = businessDates;
		this.staff = staff;
		this.numbers = numbers;
		this.clock = clock;
	}

	// ------------------------------------------------------------------ 등록

	@Override
	@Transactional
	public PaymentView register(Deposit d) {
		return register(d, null);
	}

	@Transactional
	PaymentView register(Deposit d, LoanAccounts.@Nullable PrepaymentOption prepayment) {
		if (!d.amount().isPositive()) {
			throw new BusinessException("AMOUNT_INVALID", "입금액은 0보다 커야 합니다");
		}
		LocalDate today = businessDates.today();
		if (d.bankDate().isAfter(today)) {
			throw new BusinessException("PAYMENT_DATE_INVALID", "입금일은 업무일자 이후일 수 없습니다");
		}
		if (payments.existsByBankTxId(d.bankTxId())) {
			throw new BusinessException("DUPLICATE", "이미 처리한 거래고유번호입니다: " + d.bankTxId());
		}
		Long staffId = staff.currentIfAny().map(CurrentStaff::id).orElse(null);
		Payment p = payments.save(new Payment(d.channel(), d.bankDate(), today, OffsetDateTime.now(clock),
				d.amount(), d.depositorName(), d.bankTxId(), staffId));
		if (d.accountId() == null) {
			p.unmatch();
			unmatched.save(new UnmatchedDeposit(p.getId()));
		} else {
			if (prepayment != null) {
				p.requestPrepayment(prepayment.principal() == null ? null : prepayment.principal().won(),
						prepayment.principal() == null);
			}
			p.allocateTo(d.accountId());
			payments.flush();
			accounts.applyPayment(d.accountId(), p.getId(), d.bankDate(), d.amount(), prepayment, null);
		}
		return p.view();
	}

	static String manualTxId() {
		return "MAN-" + UUID.randomUUID().toString().substring(0, 18);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<PaymentView> findByBankTxId(String bankTxId) {
		return payments.findByBankTxId(bankTxId).map(Payment::view);
	}

	@Override
	@Transactional(readOnly = true)
	public DailyTotals totalsFor(LocalDate businessDate) {
		Object[] row = payments.allocatedTotals(businessDate).getFirst();
		return new DailyTotals(((Number) row[0]).intValue(), Money.won(((Number) row[1]).longValue()),
				Money.won(unmatched.openTotal()));
	}

	// ------------------------------------------------------------------ 미확인 입금 매칭 (소급, G11)

	@Transactional
	PaymentView match(long unmatchedId, long accountId) {
		UnmatchedDeposit u = unmatched.findById(unmatchedId)
				.orElseThrow(() -> new BusinessException("UNMATCHED_NOT_FOUND", "미확인 입금이 없습니다"));
		if (u.status() != UnmatchedDeposit.Status.OPEN) {
			throw new BusinessException("UNMATCHED_CLOSED", "이미 처리된 미확인 입금입니다");
		}
		Payment p = payment(u.paymentId());
		u.match(accountId, staff.current().id(), OffsetDateTime.now(clock));
		p.allocateTo(accountId);
		payments.flush();
		accounts.applyPayment(accountId, p.getId(), p.valueDate(), p.amount(), null, null);
		return p.view();
	}

	// ------------------------------------------------------------------ 취소·정정 (R26~R31)

	record CorrectionRequest(PaymentCorrection.Type type, long paymentId, @Nullable Long targetAccountId,
			@Nullable Long correctedAmount, String reason) {
	}

	/**
	 * 당일(마감 전) 정정은 즉시 처리: 처리한 본인 또는 채권팀장 (R27).
	 * 마감 후 정정은 시스템관리자가 요청하고 채권팀장 승인 시 처리 (R28).
	 */
	@Transactional
	PaymentCorrection request(CorrectionRequest r) {
		if (r.reason() == null || r.reason().isBlank()) {
			throw new BusinessException("REASON_REQUIRED", "취소·정정 사유를 입력하세요");
		}
		Payment p = payment(r.paymentId());
		if (p.status() != Payment.Status.ALLOCATED) {
			throw new BusinessException("PAYMENT_NOT_ALLOCATED", "계약에 충당된 입금만 취소·정정할 수 있습니다");
		}
		validateTarget(r, p);
		CurrentStaff me = staff.current();
		LocalDate today = businessDates.today();
		boolean afterClose = p.businessDate().isBefore(today);
		if (afterClose) {
			if (!me.has(Permission.CLOSED_TX_CANCEL)) {
				throw new BusinessException("FORBIDDEN", "마감 후 취소는 시스템관리자가 요청합니다 (R28)");
			}
		} else if (!(me.role() == Role.COLLECTION_LEAD
				|| (me.has(Permission.PAYMENT_REGISTER) && Long.valueOf(me.id()).equals(p.processedBy())))) {
			throw new BusinessException("FORBIDDEN", "당일 취소는 처리한 본인 또는 채권팀장만 할 수 있습니다 (R27)");
		}
		PaymentCorrection c = corrections.save(new PaymentCorrection(numbers.next("correction_no_seq", "PC"),
				r.type(), p.getId(), r.targetAccountId(), r.correctedAmount(), r.reason(), afterClose, me.id(),
				today));
		if (!afterClose) {
			execute(c, p);
		}
		return c;
	}

	@Transactional
	PaymentCorrection approve(long correctionId, boolean approve) {
		PaymentCorrection c = corrections.findById(correctionId)
				.orElseThrow(() -> new BusinessException("CORRECTION_NOT_FOUND", "정정 요청이 없습니다"));
		CurrentStaff me = staff.current();
		if (!me.has(Permission.CLOSED_TX_CANCEL_APPROVE)) {
			throw new BusinessException("FORBIDDEN", "마감 후 취소 승인은 채권팀장이 합니다 (R28)");
		}
		if (c.status() != PaymentCorrection.Status.REQUESTED) {
			throw new BusinessException("CORRECTION_CLOSED", "이미 처리된 요청입니다");
		}
		if (c.requestedBy() == me.id()) {
			throw new BusinessException("SAME_APPROVER", "작성자와 승인자는 달라야 합니다");
		}
		if (!approve) {
			c.reject(me.id());
			return c;
		}
		c.approve(me.id());
		execute(c, payment(c.originalPaymentId()));
		return c;
	}

	private void execute(PaymentCorrection c, Payment p) {
		long sourceAccount = p.accountId();
		AccountRef before = accounts.get(sourceAccount);
		if (before.paidOffOn() != null) {
			throw new BusinessException("CANCEL_AFTER_PAYOFF", "완제된 계약의 입금은 취소할 수 없습니다 (R31)");
		}
		accounts.cancelPayment(sourceAccount, p.getId(), c.reason(), c.getId());
		Long newPaymentId = null;
		switch (c.type()) {
			case CANCEL -> {
				p.unmatch();
				unmatched.save(new UnmatchedDeposit(p.getId()));
			}
			case MOVE -> {
				p.allocateTo(c.targetAccountId());
				payments.flush();
				accounts.applyPayment(c.targetAccountId(), p.getId(), p.valueDate(), p.amount(), null, c.getId());
			}
			case AMOUNT_FIX -> {
				p.cancelAsInputError();
				Payment fixed = payments.save(new Payment(p.channel(), p.valueDate(), businessDates.today(),
						OffsetDateTime.now(clock), Money.won(c.correctedAmount()), p.depositorName(),
						p.bankTxId() + "-" + c.correctionNo(), staff.current().id()));
				fixed.allocateTo(sourceAccount);
				payments.flush();
				accounts.applyPayment(sourceAccount, fixed.getId(), fixed.valueDate(), fixed.amount(), null,
						c.getId());
				newPaymentId = fixed.getId();
			}
		}
		c.applied(businessDates.today(), newPaymentId);
	}

	private static void validateTarget(CorrectionRequest r, Payment p) {
		switch (r.type()) {
			case MOVE -> {
				if (r.targetAccountId() == null || r.targetAccountId().equals(p.accountId())) {
					throw new BusinessException("TARGET_INVALID", "옮길 다른 계약을 지정하세요");
				}
			}
			case AMOUNT_FIX -> {
				if (r.correctedAmount() == null || r.correctedAmount() <= 0
						|| r.correctedAmount() == p.amount().won()) {
					throw new BusinessException("AMOUNT_INVALID", "올바른 금액을 입력하세요");
				}
			}
			case CANCEL -> {
			}
		}
	}

	private Payment payment(long id) {
		return payments.findById(id).orElseThrow(() -> new BusinessException("PAYMENT_NOT_FOUND", "입금이 없습니다"));
	}
}
