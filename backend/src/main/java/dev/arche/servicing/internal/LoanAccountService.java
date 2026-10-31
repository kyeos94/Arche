package dev.arche.servicing.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.common.BusinessException;
import dev.arche.common.InterestRate;
import dev.arche.common.Money;
import dev.arche.common.RepaymentMethod;
import dev.arche.common.jpa.NumberGenerator;
import dev.arche.product.ProductCatalog;
import dev.arche.product.ProductView;
import dev.arche.servicing.AccountRef;
import dev.arche.servicing.LoanAccounts;
import dev.arche.servicing.engine.AccountStatus;
import dev.arche.servicing.engine.ScheduleMath;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class LoanAccountService implements LoanAccounts {

	private static final Logger log = LoggerFactory.getLogger(LoanAccountService.class);
	static final Set<Integer> DUE_DAYS = Set.of(5, 10, 15, 20, 25);

	private final LoanAccountRepository accounts;
	private final RateTermRepository rateTerms;
	private final DueDayTermRepository dueDayTerms;
	private final AccountEventRepository events;
	private final InstallmentRepository installments;
	private final AcquisitionRepository acquisitions;
	private final LedgerEntryRepository ledger;
	private final AccountReplayer replayer;
	private final ProductCatalog products;
	private final BusinessDates businessDates;
	private final NumberGenerator numbers;
	private final Clock clock;
	private final TransactionTemplate perAccount;

	LoanAccountService(LoanAccountRepository accounts, RateTermRepository rateTerms,
			DueDayTermRepository dueDayTerms, AccountEventRepository events, InstallmentRepository installments,
			AcquisitionRepository acquisitions, LedgerEntryRepository ledger, AccountReplayer replayer,
			ProductCatalog products, BusinessDates businessDates, NumberGenerator numbers, Clock clock,
			PlatformTransactionManager tx) {
		this.accounts = accounts;
		this.rateTerms = rateTerms;
		this.dueDayTerms = dueDayTerms;
		this.events = events;
		this.installments = installments;
		this.acquisitions = acquisitions;
		this.ledger = ledger;
		this.replayer = replayer;
		this.products = products;
		this.businessDates = businessDates;
		this.numbers = numbers;
		this.clock = clock;
		this.perAccount = new TransactionTemplate(tx);
		this.perAccount.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	// ------------------------------------------------------------------ 생성

	@Override
	@Transactional
	public AccountRef open(OpenCommand c) {
		ProductView product = products.current(c.productCode());
		if (product.repaymentMethod() == RepaymentMethod.ACQUIRED) {
			throw new BusinessException("PRODUCT_INVALID", "매입채권은 양수 등록으로 만드세요");
		}
		product.validate(c.principal(), c.termMonths(), c.graceMonths(), c.rate());
		if (!DUE_DAYS.contains(c.dueDay())) {
			throw new BusinessException("DUE_DAY_INVALID", "약정일은 5·10·15·20·25일 중에서 고릅니다");
		}
		LocalDate today = businessDates.today();
		if (c.disbursedOn().isAfter(today)) {
			throw new BusinessException("DISBURSE_DATE_INVALID", "실행일은 업무일자 이후일 수 없습니다");
		}
		if (c.applicationId() != null) {
			Optional<LoanAccount> existing = accounts.findByApplicationId(c.applicationId());
			if (existing.isPresent()) {
				return ref(existing.get()); // 이관은 멱등 (guide 2.4)
			}
		}
		LoanAccount a = accounts.save(new LoanAccount(nextAccountNo(), c.customerId(), product.id(), product.code(),
				product.productVersion(), c.applicationId(), LoanAccount.Origin.ORIGINATED,
				product.repaymentMethod(), c.principal(), c.disbursedOn(), c.termMonths() + c.graceMonths(),
				c.graceMonths(), product.prepaymentFeeRate(), c.assignedStaffId(), nextVirtualAccountNo()));
		OffsetDateTime now = OffsetDateTime.now(clock);
		rateTerms.save(new RateTerm(a.getId(), c.rate(), c.disbursedOn(), "실행", now));
		dueDayTerms.save(new DueDayTerm(a.getId(), c.dueDay(), c.disbursedOn(), c.disbursedOn(), "실행", now));
		accounts.flush();
		replay(a, null);
		return ref(a);
	}

	@Override
	@Transactional
	public AccountRef acquire(AcquireCommand c) {
		ProductView product = products.current("N1");
		LocalDate today = businessDates.today();
		if (c.acquiredOn().isAfter(today)) {
			throw new BusinessException("ACQUIRE_DATE_INVALID", "양수일은 업무일자 이후일 수 없습니다");
		}
		LoanAccount a = accounts.save(new LoanAccount(nextAccountNo(), c.customerId(), product.id(), product.code(),
				product.productVersion(), null, LoanAccount.Origin.ACQUIRED, RepaymentMethod.ACQUIRED,
				c.principal(), c.acquiredOn(), 0, 0, InterestRate.ZERO, c.assignedStaffId(),
				nextVirtualAccountNo()));
		AcquisitionRow row = acquisitions.save(new AcquisitionRow(a.getId(), c.acquiredOn(), c.acquisitionPrice(),
				c.seller(), c.originalContractDate(), c.originalRate(), c.originalLateRate(), c.principal(),
				c.preAcqInterest(), c.cost(), c.interestCharged(), c.originalAccelerationDate(),
				c.limitationStartDate(), c.judgmentConfirmedOn()));
		rateTerms.save(new RateTerm(a.getId(), c.interestCharged() ? row.appliedRate() : InterestRate.ZERO,
				c.acquiredOn(), "양수 (원채권 연체이율 승계, 상한 20%)", OffsetDateTime.now(clock)));
		accounts.flush();
		replay(a, null);
		return ref(a);
	}

	private String nextAccountNo() {
		return numbers.next("account_no_seq", "L");
	}

	private String nextVirtualAccountNo() {
		return numbers.next("account_no_seq", "990");
	}

	// ------------------------------------------------------------------ 조회

	@Override
	@Transactional(readOnly = true)
	public AccountRef get(long accountId) {
		return ref(find(accountId));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<AccountRef> findByAccountNo(String accountNo) {
		return accounts.findByAccountNo(accountNo).map(LoanAccountService::ref);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<AccountRef> findByVirtualAccountNo(String virtualAccountNo) {
		return accounts.findByVirtualAccountNo(virtualAccountNo).map(LoanAccountService::ref);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<AccountRef> findByApplicationId(long applicationId) {
		return accounts.findByApplicationId(applicationId).map(LoanAccountService::ref);
	}

	// ------------------------------------------------------------------ 입금

	@Override
	@Transactional
	public AccountRef applyPayment(long accountId, long paymentId, LocalDate bankDate, Money amount,
			@Nullable PrepaymentOption prepayment, @Nullable Long correctionId) {
		LoanAccount a = lock(accountId);
		if (bankDate.isBefore(a.disbursedOn())) {
			throw new BusinessException("PAYMENT_DATE_INVALID", "입금일이 실행(양수)일보다 이릅니다");
		}
		if (events.findByPaymentIdAndTypeAndStatus(paymentId, AccountEvent.Type.PAYMENT, "ACTIVE").isPresent()) {
			throw new BusinessException("DUPLICATE", "이미 충당된 입금입니다");
		}
		events.save(AccountEvent.payment(accountId, paymentId, bankDate, amount));
		if (prepayment != null) {
			events.save(AccountEvent.prepayment(accountId, paymentId, bankDate, prepayment.principal()));
		}
		events.flush();
		replay(a, correctionId);
		return ref(a);
	}

	@Override
	@Transactional
	public AccountRef cancelPayment(long accountId, long paymentId, String reason, @Nullable Long correctionId) {
		LoanAccount a = lock(accountId);
		if (a.status() == AccountStatus.PAID_OFF) {
			throw new BusinessException("CANCEL_AFTER_PAYOFF", "완제된 계약의 입금은 취소할 수 없습니다 (R31)");
		}
		List<AccountEvent> active = events.findByPaymentIdAndStatus(paymentId, "ACTIVE");
		if (active.isEmpty()) {
			throw new BusinessException("PAYMENT_NOT_APPLIED", "이 계약에 충당된 입금이 아닙니다");
		}
		LocalDate today = businessDates.today();
		active.forEach(e -> e.cancel(today, reason));
		events.flush();
		replay(a, correctionId);
		return ref(a);
	}

	// ------------------------------------------------------------------ 조건 변경 (R20·R21)

	@Transactional
	AccountRef changeRate(long accountId, InterestRate newRate, LocalDate validFrom, String reason) {
		LoanAccount a = lock(accountId);
		requireChangeable(a);
		if (newRate.compareTo(InterestRate.LEGAL_MAX) > 0) {
			throw new BusinessException("RATE_OVER_LIMIT", "법정 최고이자율(20%)을 넘을 수 없습니다");
		}
		List<RateTerm> history = rateTerms.findByAccountIdOrderByValidFrom(accountId);
		RateTerm current = history.getLast();
		if (!validFrom.isAfter(current.validFrom())) {
			throw new BusinessException("RATE_DATE_INVALID", "적용일은 현재 금리 적용일(" + current.validFrom() + ") 이후여야 합니다");
		}
		current.closeAt(validFrom);
		rateTerms.saveAndFlush(current);
		rateTerms.save(new RateTerm(accountId, newRate, validFrom, reason, OffsetDateTime.now(clock)));
		rateTerms.flush();
		replay(a, null);
		return ref(a);
	}

	@Transactional
	AccountRef changeDueDay(long accountId, int newDueDay, String reason) {
		LoanAccount a = lock(accountId);
		requireChangeable(a);
		if (!DUE_DAYS.contains(newDueDay)) {
			throw new BusinessException("DUE_DAY_INVALID", "약정일은 5·10·15·20·25일 중에서 고릅니다");
		}
		if (a.repaymentMethod() == RepaymentMethod.ACQUIRED) {
			throw new BusinessException("DUE_DAY_INVALID", "매입채권은 약정일이 없습니다");
		}
		LocalDate today = businessDates.today();
		List<DueDayTerm> history = dueDayTerms.findByAccountIdOrderByValidFrom(accountId);
		DueDayTerm current = history.getLast();
		if (current.dueDay() == newDueDay) {
			throw new BusinessException("DUE_DAY_SAME", "현재 약정일과 같습니다");
		}
		if (history.size() > 1 && today.isBefore(current.validFrom().plusMonths(6))) {
			throw new BusinessException("DUE_DAY_TOO_SOON",
					"약정일 변경은 6개월에 1회입니다. " + current.validFrom().plusMonths(6) + "부터 가능합니다 (R21)");
		}
		List<InstallmentRow> schedule = installments.findByAccountIdAndScheduleVersionOrderBySeq(accountId,
				a.scheduleVersion());
		int k = -1;
		for (int i = 0; i < schedule.size(); i++) {
			if (!schedule.get(i).dueDate().isBefore(today)) {
				k = i;
				break;
			}
		}
		if (k < 0 || k + 1 >= schedule.size()) {
			throw new BusinessException("DUE_DAY_NO_INSTALLMENT", "약정일을 바꿀 남은 회차가 없습니다");
		}
		LocalDate oldFirst = schedule.get(k + 1).dueDate();
		YearMonth month = YearMonth.from(oldFirst);
		if (ScheduleMath.dueDateIn(month, newDueDay).isBefore(oldFirst)) {
			month = month.plusMonths(1);
		}
		LocalDate firstNew = ScheduleMath.dueDateIn(month, newDueDay);
		current.closeAt(firstNew);
		dueDayTerms.saveAndFlush(current);
		dueDayTerms.save(new DueDayTerm(accountId, newDueDay, firstNew, today, reason, OffsetDateTime.now(clock)));
		events.save(AccountEvent.dueDayChange(accountId, today, newDueDay, reason));
		events.flush();
		replay(a, null);
		return ref(a);
	}

	@Transactional
	AccountRef chargeCost(long accountId, Money amount, String reason) {
		LoanAccount a = lock(accountId);
		requireChangeable(a);
		events.save(AccountEvent.cost(accountId, businessDates.today(), amount, reason));
		events.flush();
		replay(a, null);
		return ref(a);
	}

	private static void requireChangeable(LoanAccount a) {
		if (a.status() == AccountStatus.PAID_OFF) {
			throw new BusinessException("ACCOUNT_CLOSED", "완제된 계약입니다");
		}
	}

	// ------------------------------------------------------------------ 일마감

	@Override
	public JudgeReport judgeAll(LocalDate nextDate) {
		int count = 0;
		int notices = 0;
		int accelerations = 0;
		for (Long id : accounts.findOpenIds()) {
			AccountReplayer.Outcome o = perAccount.execute(status -> {
				LoanAccount a = lock(id);
				return replayer.replay(a, replayer.terms(a), nextDate, nextDate, null);
			});
			count++;
			notices += o.newNotices();
			accelerations += o.newlyAccelerated() ? 1 : 0;
		}
		return new JudgeReport(count, notices, accelerations);
	}

	@Override
	@Transactional(readOnly = true)
	public int reconcile() {
		int mismatches = 0;
		for (LoanAccount a : accounts.findAll()) {
			long principal = a.initialPrincipal().won() - ledger.sumPrincipalRepaid(a.getId());
			long suspense = ledger.sumSuspense(a.getId());
			if (principal != a.principalBalance() || suspense != a.suspenseBalance()) {
				log.warn("잔액 대사 불일치 계약 {}: 원금 {} / 원장 {}, 가수금 {} / 원장 {}", a.accountNo(),
						a.principalBalance(), principal, a.suspenseBalance(), suspense);
				mismatches++;
			}
		}
		return mismatches;
	}

	@Override
	@Transactional(readOnly = true)
	public Money correctionsPostedOn(LocalDate businessDate) {
		return Money.won(ledger.sumCorrectionsPostedOn(businessDate));
	}

	// ------------------------------------------------------------------ 공통

	private LoanAccount lock(long id) {
		return accounts.lockById(id)
				.orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "계약이 없습니다: " + id));
	}

	private LoanAccount find(long id) {
		return accounts.findById(id)
				.orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "계약이 없습니다: " + id));
	}

	private void replay(LoanAccount a, @Nullable Long correctionId) {
		LocalDate today = businessDates.today();
		LocalDate asOf = a.asOf() != null && a.asOf().isAfter(today) ? a.asOf() : today;
		replayer.replay(a, replayer.terms(a), asOf, today, correctionId);
	}

	static AccountRef ref(LoanAccount a) {
		return new AccountRef(a.getId(), a.accountNo(), a.status().name(), a.repaymentMethod().name(),
				a.assignedStaffId(), a.paidOffOn(), a.principalBalance(), a.payoffAmount());
	}
}
