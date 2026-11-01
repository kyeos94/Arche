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
	private final AdjustmentRequestRepository adjustmentRequests;
	private final LimitationService limitationService;
	private final ProductCatalog products;
	private final BusinessDates businessDates;
	private final NumberGenerator numbers;
	private final Clock clock;
	private final TransactionTemplate perAccount;

	LoanAccountService(LoanAccountRepository accounts, RateTermRepository rateTerms,
			DueDayTermRepository dueDayTerms, AccountEventRepository events, InstallmentRepository installments,
			AcquisitionRepository acquisitions, LedgerEntryRepository ledger, AccountReplayer replayer,
			AdjustmentRequestRepository adjustmentRequests, LimitationService limitationService,
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
		this.adjustmentRequests = adjustmentRequests;
		this.limitationService = limitationService;
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
		List<AccountEvent> active = events.findByPaymentIdAndStatus(paymentId, "ACTIVE");
		if (active.isEmpty()) {
			throw new BusinessException("PAYMENT_NOT_APPLIED", "이 계약에 충당된 입금이 아닙니다");
		}
		LocalDate bankDate = active.stream().filter(e -> e.type() == AccountEvent.Type.PAYMENT)
				.map(AccountEvent::eventDate).findFirst().orElse(active.getFirst().eventDate());
		// R31: 완제·매각·상각 확정 전 입금은 취소 불가. 완제 취소(R43) 승인이 있으면 1건 허용하고 소진
		AdjustmentRequest unlock = null;
		if (restrictedByR31(a, bankDate)) {
			unlock = adjustmentRequests.findFirstByAccountIdAndTypeAndStatusOrderById(accountId,
					AdjustmentRequest.Type.REVERT_PAYOFF, AdjustmentRequest.Status.APPLIED)
					.orElseThrow(() -> new BusinessException("CANCEL_AFTER_PAYOFF",
							"완제·매각·상각 확정 전 입금은 취소할 수 없습니다. 완제 취소(R43) 승인을 먼저 받으세요 (R31)"));
		}
		LocalDate today = businessDates.today();
		active.forEach(e -> e.cancel(today, reason));
		events.flush();
		replay(a, correctionId);
		LifecycleService.requireNonNegative(a);
		if (unlock != null) {
			unlock.consumed(today);
		}
		return ref(a);
	}

	/** R31 제한 대상: 완제 계약의 입금, 매각·상각 계약의 매각일·상각일 전 입금 */
	static boolean restrictedByR31(LoanAccount a, LocalDate bankDate) {
		return switch (a.status()) {
			case PAID_OFF -> true;
			case SOLD -> a.soldOn() != null && bankDate.isBefore(a.soldOn());
			case CHARGED_OFF -> a.chargedOffOn() != null && bankDate.isBefore(a.chargedOffOn());
			default -> false;
		};
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

	// ------------------------------------------------------------------ 채무조정 (R9·R23)

	static final int MAX_WORKOUT_INSTALLMENTS = 60;

	record WorkoutCommand(Money waiveLate, Money waiveNormal, Money waivePrincipal, int installments, int dueDay,
			String reason) {
	}

	/**
	 * 채무조정 체결. 업무일자가 체결일이며, 미감면 이자·비용은 같은 날 입금으로 먼저 받아 둬야 한다.
	 * 재생 결과 체결되지 않으면 예외로 트랜잭션 전체를 되돌린다 (R23).
	 */
	@Transactional
	AccountRef workout(long accountId, WorkoutCommand c) {
		LoanAccount a = lock(accountId);
		requireChangeable(a);
		if (a.repaymentMethod() == RepaymentMethod.ACQUIRED) {
			throw new BusinessException("WORKOUT_INVALID", "매입채권 채무조정은 범위 밖입니다");
		}
		validateWorkout(c);
		LocalDate today = businessDates.today();
		AccountEvent event = events.save(AccountEvent.workout(accountId, today, c.waiveLate(), c.waiveNormal(),
				c.waivePrincipal(), c.installments(), c.dueDay(), c.reason()));
		events.flush();
		AccountReplayer.Outcome o = replayer.replay(a, replayer.terms(a), replayAsOf(a), today, null);
		dev.arche.servicing.engine.WorkoutView result = o.result().workouts().stream()
				.filter(w -> w.ref().equals(event.ref())).findFirst().orElseThrow();
		if (!result.concluded()) {
			throw new BusinessException("WORKOUT_UNPAID",
					"미감면 이자·비용 " + result.requiredPayment() + "을 오늘 먼저 입금해야 체결됩니다 (R23)");
		}
		LocalDate firstDue = ScheduleMath.firstDueDate(today, c.dueDay());
		List<DueDayTerm> history = dueDayTerms.findByAccountIdOrderByValidFrom(accountId);
		DueDayTerm current = history.getLast();
		if (current.dueDay() != c.dueDay()) {
			current.closeAt(firstDue);
			dueDayTerms.saveAndFlush(current);
			dueDayTerms.save(new DueDayTerm(accountId, c.dueDay(), firstDue, today, "채무조정", OffsetDateTime.now(clock)));
		}
		return ref(a);
	}

	static void validateWorkout(WorkoutCommand c) {
		if (c.installments() < 1 || c.installments() > MAX_WORKOUT_INSTALLMENTS) {
			throw new BusinessException("WORKOUT_INVALID", "분할은 1~" + MAX_WORKOUT_INSTALLMENTS + "회입니다");
		}
		if (!DUE_DAYS.contains(c.dueDay())) {
			throw new BusinessException("DUE_DAY_INVALID", "약정일은 5·10·15·20·25일 중에서 고릅니다");
		}
		if (c.waiveLate().isNegative() || c.waiveNormal().isNegative() || c.waivePrincipal().isNegative()) {
			throw new BusinessException("WORKOUT_INVALID", "감면액은 0 이상이어야 합니다");
		}
		if (c.reason() == null || c.reason().isBlank()) {
			throw new BusinessException("REASON_REQUIRED", "채무조정 사유를 입력하세요");
		}
	}

	private LocalDate replayAsOf(LoanAccount a) {
		LocalDate today = businessDates.today();
		return a.asOf() != null && a.asOf().isAfter(today) ? a.asOf() : today;
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
		if (a.status() == AccountStatus.SOLD) {
			throw new BusinessException("ACCOUNT_SOLD", "매각된 채권은 조건을 바꾸거나 비용을 적재할 수 없습니다 (R40)");
		}
		if (a.status() == AccountStatus.CHARGED_OFF) {
			throw new BusinessException("ACCOUNT_CHARGED_OFF", "상각 채권입니다. 상각 취소(R43) 후 진행하세요 (R39)");
		}
	}

	// ------------------------------------------------------------------ 추심 중지와 이자·금리 (R45·R46)

	@Override
	@Transactional(readOnly = true)
	public List<AccountRef> sameCustomerOpenAccounts(long accountId) {
		LoanAccount a = find(accountId);
		return accounts.findByCustomerIdOrderById(a.customerId()).stream()
				.filter(x -> x.status() != AccountStatus.PAID_OFF && x.status() != AccountStatus.SOLD)
				.map(LoanAccountService::ref).toList();
	}

	@Override
	@Transactional
	public long suspendInterest(long accountId, LocalDate from, String reason) {
		LoanAccount a = lock(accountId);
		if (a.status() == AccountStatus.PAID_OFF || a.status() == AccountStatus.SOLD) {
			throw new BusinessException("ACCOUNT_CLOSED", "완제·매각된 계약입니다");
		}
		LocalDate start = from.isBefore(a.disbursedOn()) ? a.disbursedOn() : from;
		AccountEvent e = events.saveAndFlush(AccountEvent.interestSuspension(accountId, start, reason));
		replay(a, null);
		return e.getId();
	}

	@Override
	@Transactional
	public void endInterestSuspension(long accountId, long eventId, LocalDate on) {
		LoanAccount a = lock(accountId);
		AccountEvent e = events.findById(eventId).filter(x -> x.accountId() == accountId
				&& x.type() == AccountEvent.Type.INTEREST_SUSPENSION && x.active())
				.orElseThrow(() -> new BusinessException("EVENT_NOT_FOUND", "이자 부과 중단 기록이 없습니다"));
		e.endSuspension(on);
		events.flush();
		replay(a, null);
	}

	@Override
	@Transactional
	public AccountRef lowerRate(long accountId, InterestRate rate, LocalDate validFrom, String reason) {
		LoanAccount a = find(accountId);
		List<RateTerm> history = rateTerms.findByAccountIdOrderByValidFrom(accountId);
		InterestRate current = replayer.terms(a).rateAt(validFrom);
		if (rate.compareTo(current) > 0) {
			throw new BusinessException("RATE_INVALID", "조정 금리는 현재 적용 이율(" + current.percent() + "%) 이하여야 합니다 (R46)");
		}
		if (history.isEmpty()) {
			throw new BusinessException("RATE_INVALID", "금리 이력이 없습니다");
		}
		return changeRate(accountId, rate, validFrom, reason);
	}

	// ------------------------------------------------------------------ 일마감

	@Override
	public JudgeReport judgeAll(LocalDate nextDate) {
		int count = 0;
		int notices = 0;
		int accelerations = 0;
		for (Long id : accounts.findIdsToJudge(nextDate)) {
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
	public dev.arche.servicing.LimitationInfo limitation(long accountId) {
		return limitationService.of(find(accountId));
	}

	@Override
	@Transactional(readOnly = true)
	public List<PaymentRecord> payments(long accountId) {
		return events.findByAccountIdOrderById(accountId).stream()
				.filter(e -> e.active() && e.type() == AccountEvent.Type.PAYMENT)
				.map(e -> new PaymentRecord(e.eventDate(), Money.won(e.amount()))).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public List<AccountRef> openAccounts() {
		return accounts.findOpenIds().stream().map(id -> ref(find(id))).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public List<MessagingView> messagingViews(LocalDate paidOffOn) {
		List<MessagingView> views = new java.util.ArrayList<>();
		for (Long id : accounts.findOpenIds()) {
			views.add(messaging(find(id)));
		}
		for (LoanAccount a : accounts.findByPaidOffOn(paidOffOn)) {
			views.add(messaging(a));
		}
		return views;
	}

	@Override
	@Transactional(readOnly = true)
	public MessagingView messagingView(long accountId) {
		return messaging(find(accountId));
	}

	private MessagingView messaging(LoanAccount a) {
		List<InstallmentRow> current = installments.findByAccountIdAndScheduleVersionOrderBySeq(a.getId(),
				a.scheduleVersion());
		InstallmentRow next = current.stream().filter(i -> a.nextDueDate() != null && i.dueDate().equals(a.nextDueDate()))
				.findFirst().orElse(null);
		LocalDate delinquencyStart = current.stream().filter(i -> "OVERDUE".equals(i.status()))
				.map(i -> i.graceEnd().plusDays(1)).min(LocalDate::compareTo).orElse(null);
		return new MessagingView(a.getId(), a.accountNo(), a.customerId(), a.status().name(),
				a.repaymentMethod().name(), a.assignedStaffId(), a.nextDueDate(),
				next == null ? null : next.principalDue() - next.principalPaid() + next.plannedInterest(),
				a.delinquentSince(), delinquencyStart, a.paidOffOn(), a.payoffAmount());
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
