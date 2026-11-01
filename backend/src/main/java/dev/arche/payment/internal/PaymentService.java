package dev.arche.payment.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.common.BusinessException;
import dev.arche.common.Money;
import dev.arche.common.jpa.NumberGenerator;
import dev.arche.payment.Payments;
import dev.arche.payment.internal.split.AllocationStrategy;
import dev.arche.servicing.AccountRef;
import dev.arche.servicing.LoanAccounts;
import dev.arche.staff.CurrentStaff;
import dev.arche.staff.DataScope;
import dev.arche.staff.Permission;
import dev.arche.staff.Role;
import dev.arche.staff.StaffDirectory;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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

	List<Payment> findTop500ByOrderByIdDesc();

	List<Payment> findByParentPaymentIdOrderById(Long parentPaymentId);

	List<Payment> findByLegalActionIdOrderById(Long legalActionId);

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

interface SubrogationClaimRepository extends JpaRepository<SubrogationClaim, Long> {

	List<SubrogationClaim> findByAccountIdOrderByIdDesc(long accountId);

	List<SubrogationClaim> findTop500ByOrderByIdDesc();

	Optional<SubrogationClaim> findByPaymentIdAndStatus(long paymentId, SubrogationClaim.Status status);
}

/**
 * 입금 등록·매칭·취소·정정 (정책서 4장, 10장 R26~R31), 일괄 배분 (R47·R48), 입금자 구분·대위변제 (R49·R50).
 */
@Service
class PaymentService implements Payments {

	private final PaymentRepository payments;
	private final UnmatchedDepositRepository unmatched;
	private final PaymentCorrectionRepository corrections;
	private final SubrogationClaimRepository claims;
	private final LoanAccounts accounts;
	private final BusinessDates businessDates;
	private final StaffDirectory staff;
	private final NumberGenerator numbers;
	private final Clock clock;

	PaymentService(PaymentRepository payments, UnmatchedDepositRepository unmatched,
			PaymentCorrectionRepository corrections, SubrogationClaimRepository claims, LoanAccounts accounts,
			BusinessDates businessDates, StaffDirectory staff, NumberGenerator numbers, Clock clock) {
		this.payments = payments;
		this.unmatched = unmatched;
		this.corrections = corrections;
		this.claims = claims;
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
		return register(d, prepayment, Payer.CUSTOMER, null);
	}

	/**
	 * @param legalActionId 배당금·공탁금 입금이 연결될 법조치 (R52). 검증은 호출자(컨트롤러)가 한다
	 */
	@Transactional
	PaymentView register(Deposit d, LoanAccounts.@Nullable PrepaymentOption prepayment, Payer payer,
			@Nullable Long legalActionId) {
		if (d.channel().court() && !payer.isCustomer()) {
			throw new BusinessException("PAYER_INVALID", "배당금·공탁금 입금은 채무자 재산에서 나온 돈이라 입금자 구분이 본인입니다 (R52)");
		}
		if (legalActionId != null && !d.channel().court()) {
			throw new BusinessException("LEGAL_ACTION_INVALID", "법조치 연결은 배당금·공탁금 입금에만 합니다 (R52)");
		}
		Payment p = newPayment(d);
		p.payer(payer);
		if (legalActionId != null) {
			p.linkLegalAction(legalActionId);
		}
		if (d.accountId() == null) {
			p.unmatch();
			unmatched.save(new UnmatchedDeposit(p.getId()));
		} else {
			if (prepayment != null) {
				p.requestPrepayment(prepayment.principal() == null ? null : prepayment.principal().won(),
						prepayment.principal() == null);
			}
			apply(p, d.accountId(), prepayment, null);
		}
		return p.view();
	}

	private Payment newPayment(Deposit d) {
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
		return payments.save(new Payment(d.channel(), d.bankDate(), today, OffsetDateTime.now(clock), d.amount(),
				d.depositorName(), d.bankTxId(), currentStaffId()));
	}

	/**
	 * 계약에 충당하고 입금자 구분에 따른 부수 기록을 남긴다: 채무자 승인이 아니면 소멸시효 미반영(R50), 보증인·제3자면 대위변제 기록(R49).
	 */
	private void apply(Payment p, long accountId, LoanAccounts.@Nullable PrepaymentOption prepayment,
			@Nullable Long correctionId) {
		p.allocateTo(accountId);
		payments.flush();
		accounts.applyPayment(accountId, p.getId(), p.valueDate(), p.amount(), prepayment, correctionId);
		if (!p.acknowledgesDebt()) {
			accounts.excludeFromLimitation(accountId, p.getId());
		}
		if (!p.payer().isCustomer()) {
			claims.save(new SubrogationClaim(p, accountId, businessDates.today()));
		}
	}

	private void cancelClaim(Payment p, String reason) {
		claims.findByPaymentIdAndStatus(p.getId(), SubrogationClaim.Status.ACTIVE)
				.ifPresent(c -> c.cancel(businessDates.today(), reason));
		claims.flush();
	}

	private @Nullable Long currentStaffId() {
		return staff.currentIfAny().map(CurrentStaff::id).orElse(null);
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
		UnmatchedDeposit u = openUnmatched(unmatchedId);
		Payment p = payment(u.paymentId());
		u.match(accountId, staff.current().id(), OffsetDateTime.now(clock));
		apply(p, accountId, null, null);
		return p.view();
	}

	private UnmatchedDeposit openUnmatched(long unmatchedId) {
		UnmatchedDeposit u = unmatched.findById(unmatchedId)
				.orElseThrow(() -> new BusinessException("UNMATCHED_NOT_FOUND", "미확인 입금이 없습니다"));
		if (u.status() != UnmatchedDeposit.Status.OPEN) {
			throw new BusinessException("UNMATCHED_CLOSED", "이미 처리된 미확인 입금입니다");
		}
		return u;
	}

	// ------------------------------------------------------------------ 일괄 배분 (R47·R48)

	/** 배분 대상: 고객의 완제 전 계약 중 담당 범위 안의 것 (R47) */
	@Transactional(readOnly = true)
	List<LoanAccounts.DueSnapshot> splitTargets(long customerId) {
		DataScope scope = staff.loanScope();
		return accounts.customerAccounts(customerId).stream().filter(a -> scope.allows(a.assignedStaffId()))
				.toList();
	}

	@Transactional(readOnly = true)
	List<AllocationStrategy.Part> previewSplit(long customerId, AllocationStrategy.Method method, Money total,
			Map<Long, Money> manual) {
		return AllocationStrategy.of(method).split(total, targets(splitTargets(customerId)), manual);
	}

	record SplitCommand(long customerId, AllocationStrategy.Method method, Map<Long, Money> manual,
			@Nullable Long unmatchedId, @Nullable Deposit deposit, @Nullable Payer payer) {
	}

	record SplitResult(PaymentView parent, List<PaymentView> parts) {
	}

	/**
	 * 새 입금(deposit) 또는 미확인 입금(unmatchedId)을 나눠 충당한다. 한 트랜잭션, 계약 id 오름차순 (R48).
	 */
	@Transactional
	SplitResult split(SplitCommand c) {
		Payment parent;
		if (c.unmatchedId() != null) {
			UnmatchedDeposit u = openUnmatched(c.unmatchedId());
			parent = payment(u.paymentId());
			u.split(staff.current().id(), OffsetDateTime.now(clock));
			if (c.payer() != null && !c.payer().isCustomer()) {
				parent.payer(c.payer());
			}
		} else if (c.deposit() != null) {
			if (c.deposit().channel().court()) {
				throw new BusinessException("SPLIT_COURT_CHANNEL", "배당금·공탁금은 법조치가 있는 계약에 바로 입금합니다 (R52)");
			}
			parent = newPayment(c.deposit());
			parent.payer(c.payer() == null ? Payer.CUSTOMER : c.payer());
		} else {
			throw new BusinessException("SPLIT_SOURCE_REQUIRED", "배분할 입금을 지정하세요");
		}
		List<AllocationStrategy.Part> parts = AllocationStrategy.of(c.method()).split(parent.amount(),
				targets(splitTargets(c.customerId())), c.manual());
		parent.markSplit(c.method());
		payments.flush();
		LocalDate today = businessDates.today();
		Long me = currentStaffId();
		List<PaymentView> views = new java.util.ArrayList<>();
		// 취소 후 다시 배분하면 이전 하위 입금 번호와 겹치지 않게 이어서 붙인다
		int n = payments.findByParentPaymentIdOrderById(parent.getId()).size();
		for (AllocationStrategy.Part part : parts) {
			Payment child = payments.save(new Payment(parent.channel(), parent.valueDate(), today,
					OffsetDateTime.now(clock), part.amount(), parent.depositorName(),
					parent.bankTxId() + "-S" + (++n), me));
			child.childOf(parent);
			apply(child, part.accountId(), null, null);
			views.add(child.view());
		}
		return new SplitResult(parent.view(), views);
	}

	@Transactional(readOnly = true)
	List<PaymentView> children(long parentPaymentId) {
		return payments.findByParentPaymentIdOrderById(parentPaymentId).stream().map(Payment::view).toList();
	}

	private static List<AllocationStrategy.Target> targets(List<LoanAccounts.DueSnapshot> snapshots) {
		return snapshots.stream().map(s -> new AllocationStrategy.Target(s.accountId(), s.accountNo(),
				s.delinquentSince(), s.nextDueDate(), s.overdue(), s.nextDue(), s.payoff())).toList();
	}

	// ------------------------------------------------------------------ 취소·정정 (R26~R31, 일괄 배분 R48)

	record CorrectionRequest(PaymentCorrection.Type type, long paymentId, @Nullable Long targetAccountId,
			@Nullable Long correctedAmount, String reason) {
	}

	/**
	 * 당일(마감 전) 정정은 즉시 처리: 처리한 본인 또는 채권팀장 (R27).
	 * 마감 후 정정은 시스템관리자가 요청하고 채권팀장 승인 시 처리 (R28).
	 * 일괄 배분은 원 입금 단위 CANCEL만 (R48): 처리일·처리자는 하위 입금(배분 처리) 기준.
	 */
	@Transactional
	PaymentCorrection request(CorrectionRequest r) {
		if (r.reason() == null || r.reason().isBlank()) {
			throw new BusinessException("REASON_REQUIRED", "취소·정정 사유를 입력하세요");
		}
		Payment p = payment(r.paymentId());
		if (p.parentPaymentId() != null) {
			throw new BusinessException("SPLIT_CHILD",
					"일괄 배분된 입금은 원 입금(#" + p.parentPaymentId() + ") 단위로만 취소합니다 (R48)");
		}
		boolean group = p.status() == Payment.Status.SPLIT;
		if (group && r.type() != PaymentCorrection.Type.CANCEL) {
			throw new BusinessException("SPLIT_CANCEL_ONLY", "일괄 배분은 전체 취소만 할 수 있습니다 (R48)");
		}
		if (!group && p.status() != Payment.Status.ALLOCATED) {
			throw new BusinessException("PAYMENT_NOT_ALLOCATED", "계약에 충당된 입금만 취소·정정할 수 있습니다");
		}
		validateTarget(r, p);
		Payment processed = group ? activeChildren(p).getFirst() : p;
		CurrentStaff me = staff.current();
		LocalDate today = businessDates.today();
		boolean afterClose = processed.businessDate().isBefore(today);
		if (afterClose) {
			if (!me.has(Permission.CLOSED_TX_CANCEL)) {
				throw new BusinessException("FORBIDDEN", "마감 후 취소는 시스템관리자가 요청합니다 (R28)");
			}
		} else if (!(me.role() == Role.COLLECTION_LEAD
				|| (me.has(Permission.PAYMENT_REGISTER) && Long.valueOf(me.id()).equals(processed.processedBy())))) {
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
		if (p.status() == Payment.Status.SPLIT) {
			executeGroupCancel(c, p);
			return;
		}
		long sourceAccount = p.accountId();
		AccountRef before = accounts.get(sourceAccount);
		if (before.paidOffOn() != null) {
			throw new BusinessException("CANCEL_AFTER_PAYOFF", "완제된 계약의 입금은 취소할 수 없습니다 (R31)");
		}
		accounts.cancelPayment(sourceAccount, p.getId(), c.reason(), c.getId());
		cancelClaim(p, c.reason());
		Long newPaymentId = null;
		switch (c.type()) {
			case CANCEL -> {
				p.unmatch();
				unmatched.save(new UnmatchedDeposit(p.getId()));
			}
			case MOVE -> apply(p, c.targetAccountId(), null, c.getId());
			case AMOUNT_FIX -> {
				p.cancelAsInputError();
				Payment fixed = payments.save(new Payment(p.channel(), p.valueDate(), businessDates.today(),
						OffsetDateTime.now(clock), Money.won(c.correctedAmount()), p.depositorName(),
						p.bankTxId() + "-" + c.correctionNo(), staff.current().id()));
				fixed.payer(p.payer());
				if (p.legalActionId() != null) {
					fixed.linkLegalAction(p.legalActionId());
				}
				apply(fixed, sourceAccount, null, c.getId());
				newPaymentId = fixed.getId();
			}
		}
		c.applied(businessDates.today(), newPaymentId);
	}

	/** R48: 하위 입금 전부 취소(R26) → 원 입금 전액을 미확인 입금으로. 하나라도 취소할 수 없으면 전체 불가 (R31) */
	private void executeGroupCancel(PaymentCorrection c, Payment parent) {
		List<Payment> children = activeChildren(parent).stream()
				.sorted(Comparator.comparing(Payment::accountId)).toList();
		for (Payment child : children) {
			if (accounts.get(child.accountId()).paidOffOn() != null) {
				throw new BusinessException("CANCEL_AFTER_PAYOFF",
						"배분 대상 계약 중 완제된 계약이 있어 취소할 수 없습니다 (R31·R48)");
			}
		}
		for (Payment child : children) {
			accounts.cancelPayment(child.accountId(), child.getId(), c.reason(), c.getId());
			cancelClaim(child, c.reason());
			child.cancelAsInputError();
		}
		parent.unmatch();
		unmatched.save(new UnmatchedDeposit(parent.getId()));
		c.applied(businessDates.today(), null);
	}

	private List<Payment> activeChildren(Payment parent) {
		List<Payment> children = payments.findByParentPaymentIdOrderById(parent.getId()).stream()
				.filter(x -> x.status() == Payment.Status.ALLOCATED).toList();
		if (children.isEmpty()) {
			throw new BusinessException("SPLIT_EMPTY", "취소할 배분 내역이 없습니다");
		}
		return children;
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

	Payment payment(long id) {
		return payments.findById(id).orElseThrow(() -> new BusinessException("PAYMENT_NOT_FOUND", "입금이 없습니다"));
	}
}
