package dev.arche.servicing.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.common.BusinessException;
import dev.arche.common.InterestRate;
import dev.arche.common.Money;
import dev.arche.common.RepaymentMethod;
import dev.arche.customer.CustomerView;
import dev.arche.customer.Customers;
import dev.arche.product.ProductCatalog;
import dev.arche.product.ProductView;
import dev.arche.servicing.AccountRef;
import dev.arche.servicing.LoanAccounts;
import dev.arche.servicing.engine.AccountStatus;
import dev.arche.servicing.engine.LimitationPeriod;
import dev.arche.servicing.engine.ScheduleMath;
import dev.arche.servicing.engine.SimulationResult;
import dev.arche.staff.DataScope;
import dev.arche.staff.StaffDirectory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
 * 계약 목록·상세·조건 변경 API. 조회는 데이터 범위(담당 건 / 팀 전체 / 전체)를 적용한다 (정책서 9.1).
 */
@RestController
@RequestMapping("/api/loans")
class LoanController {

	// ------------------------------------------------------------------ DTO

	record ListItem(long id, String accountNo, String customerName, String productCode, String status,
			long principalBalance, long payoffAmount, int delinquencyDays, LocalDate nextDueDate,
			Long assignedStaffId) {
	}

	record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
		static <T> PageResponse<T> of(Page<T> p) {
			return new PageResponse<>(p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(),
					p.getTotalPages());
		}
	}

	record Detail(long id, String accountNo, CustomerView customer, String productCode, String productName,
			String repaymentMethod, String origin, String status, long initialPrincipal, LocalDate disbursedOn,
			LocalDate maturityDate, int termMonths, int graceMonths, BigDecimal currentRate, BigDecimal lateRate,
			Integer currentDueDay, Long installmentAmount, long principalBalance, long suspenseBalance,
			long costBalance, long preAcqInterest, long outstandingNormalInterest, long outstandingLateInterest,
			long payoffAmount, long prepaymentFeeToday, int delinquencyDays, LocalDate delinquentSince,
			LocalDate nextDueDate, LocalDate acceleratedOn, LocalDate paidOffOn, LocalDate lastSettledOn,
			LocalDate asOf, LocalDate businessDate, String virtualAccountNo, long totalPaid, long refundPending,
			Long assignedStaffId, LocalDate limitationCompletionDate, Acquisition acquisition) {
	}

	record Acquisition(LocalDate acquiredOn, long acquisitionPrice, String seller, BigDecimal appliedRate,
			String interestMode, BigDecimal recoveryRate, Long daysSinceLastPayment) {
	}

	record InstallmentDto(int scheduleVersion, int seq, LocalDate periodStart, LocalDate dueDate, LocalDate graceEnd,
			long principalDue, long plannedInterest, long plannedPayment, long principalPaid, String status) {
	}

	record Schedule(int currentVersion, List<InstallmentDto> current, Map<Integer, List<InstallmentDto>> previous) {
	}

	record AllocationDto(Integer installmentSeq, String component, long amount) {
	}

	record LedgerDto(long id, String type, LocalDate valueDate, LocalDate businessDate, OffsetDateTime postedAt,
			String sourceRef, long amount, long principal, long normalInterest, long lateInterest, long cost,
			long fee, long preAcqInterest, long suspense, long refund, long normalAccrued, long lateAccrued,
			long principalBalanceAfter, Long paymentId, Long correctionId, Long reversalOfId, boolean superseded,
			List<AllocationDto> allocations) {
	}

	record RateTermDto(BigDecimal rate, LocalDate validFrom, LocalDate validTo, String reason,
			OffsetDateTime recordedAt) {
	}

	record DueDayTermDto(int dueDay, LocalDate validFrom, LocalDate validTo, LocalDate requestedOn, String reason,
			OffsetDateTime recordedAt) {
	}

	record NoticeDto(LocalDate reasonDate, LocalDate sentOn, LocalDate deemedArrivalOn, LocalDate actualArrivalOn,
			LocalDate accelerationDate, String status, LocalDate closedOn) {
	}

	record EventDto(long id, String ref, String type, LocalDate eventDate, Long amount, Integer newDueDay,
			Long paymentId, String status, LocalDate cancelledOn, String reason, String createdBy,
			OffsetDateTime createdAt) {
	}

	record History(List<RateTermDto> rateTerms, List<DueDayTermDto> dueDayTerms, List<NoticeDto> notices,
			List<EventDto> events) {
	}

	record Quote(LocalDate date, long cost, long lateInterest, long normalInterest, long overduePrincipal,
			long principal, long fee, long suspense, long total, boolean full) {
	}

	record OpenRequest(@NotNull Long customerId, @NotBlank String productCode, @Positive long principal,
			@NotNull BigDecimal rate, @Positive int termMonths, int graceMonths, int dueDay, LocalDate disbursedOn,
			Long assignedStaffId) {
	}

	record AcquireRequest(@NotNull Long customerId, @NotNull LocalDate acquiredOn, @Positive long acquisitionPrice,
			@NotBlank String seller, @NotNull LocalDate originalContractDate, @NotNull BigDecimal originalRate,
			BigDecimal originalLateRate, @Positive long principal, long preAcqInterest, long cost,
			boolean interestCharged, LocalDate originalAccelerationDate, LocalDate limitationStartDate,
			LocalDate judgmentConfirmedOn, Long assignedStaffId) {
	}

	record AcquireResponse(AccountRef account, List<String> warnings) {
	}

	record RateChangeRequest(@NotNull BigDecimal rate, @NotNull LocalDate validFrom, @NotBlank String reason) {
	}

	record DueDayChangeRequest(int dueDay, @NotBlank String reason) {
	}

	record CostRequest(@Positive long amount, @NotBlank String reason) {
	}

	// ------------------------------------------------------------------

	private final LoanAccountRepository accounts;
	private final RateTermRepository rateTerms;
	private final DueDayTermRepository dueDayTerms;
	private final InstallmentRepository installments;
	private final LedgerEntryRepository ledger;
	private final AccelerationNoticeRepository notices;
	private final AccountEventRepository events;
	private final AcquisitionRepository acquisitions;
	private final AccountReplayer replayer;
	private final LoanAccountService service;
	private final Customers customers;
	private final ProductCatalog products;
	private final StaffDirectory staff;
	private final BusinessDates businessDates;

	LoanController(LoanAccountRepository accounts, RateTermRepository rateTerms, DueDayTermRepository dueDayTerms,
			InstallmentRepository installments, LedgerEntryRepository ledger, AccelerationNoticeRepository notices,
			AccountEventRepository events, AcquisitionRepository acquisitions, AccountReplayer replayer,
			LoanAccountService service, Customers customers, ProductCatalog products, StaffDirectory staff,
			BusinessDates businessDates) {
		this.accounts = accounts;
		this.rateTerms = rateTerms;
		this.dueDayTerms = dueDayTerms;
		this.installments = installments;
		this.ledger = ledger;
		this.notices = notices;
		this.events = events;
		this.acquisitions = acquisitions;
		this.replayer = replayer;
		this.service = service;
		this.customers = customers;
		this.products = products;
		this.staff = staff;
		this.businessDates = businessDates;
	}

	// ------------------------------------------------------------------ 목록

	@GetMapping
	@PreAuthorize("hasAuthority('loan.read')")
	@Transactional(readOnly = true)
	PageResponse<ListItem> list(@RequestParam(required = false) String status,
			@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size) {
		DataScope scope = staff.loanScope();
		boolean all = scope.kind() == DataScope.Kind.ALL;
		Set<Long> staffIds = all ? Set.of(-1L) : nonEmpty(scope.staffIds());
		String accountNo = null;
		Set<Long> customerIds = Set.of(-1L);
		boolean noCustomerFilter = true;
		if (q != null && !q.isBlank()) {
			if (q.matches("[0-9]{6}-?[0-9]{7}")) {
				noCustomerFilter = false;
				customerIds = customers.findByResidentNo(q).map(c -> Set.of(c.id())).orElse(Set.of(-1L));
			} else {
				accountNo = q.trim();
			}
		}
		Page<LoanAccount> result = accounts.search(blankToNull(status), all, staffIds, accountNo, noCustomerFilter,
				customerIds, PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "id")));
		return PageResponse.of(result.map(a -> new ListItem(a.getId(), a.accountNo(),
				customers.get(a.customerId()).name(), a.productCode(), a.status().name(), a.principalBalance(),
				a.payoffAmount(), a.delinquencyDays(), a.nextDueDate(), a.assignedStaffId())));
	}

	@GetMapping("/status-counts")
	@PreAuthorize("hasAuthority('loan.read')")
	@Transactional(readOnly = true)
	Map<String, Long> statusCounts() {
		DataScope scope = staff.loanScope();
		boolean all = scope.kind() == DataScope.Kind.ALL;
		Map<String, Long> counts = new LinkedHashMap<>();
		for (AccountStatus s : AccountStatus.values()) {
			counts.put(s.name(), 0L);
		}
		for (Object[] row : accounts.countByStatus(all, all ? Set.of(-1L) : nonEmpty(scope.staffIds()))) {
			counts.put((String) row[0], (Long) row[1]);
		}
		return counts;
	}

	// ------------------------------------------------------------------ 상세

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('loan.read')")
	@Transactional(readOnly = true)
	Detail detail(@PathVariable long id) {
		LoanAccount a = visible(id);
		ProductView product = products.byId(a.productId());
		List<RateTerm> rates = rateTerms.findByAccountIdOrderByValidFrom(id);
		List<DueDayTerm> dueDays = dueDayTerms.findByAccountIdOrderByValidFrom(id);
		LocalDate today = businessDates.today();
		BigDecimal currentRate = rates.isEmpty() ? null : currentRate(rates, today).percent();
		Integer currentDueDay = dueDays.isEmpty() ? null : dueDays.getLast().dueDay();
		AcquisitionRow acq = acquisitions.findByAccountId(id).orElse(null);
		List<LocalDate> paymentDates = events.findByAccountIdOrderById(id).stream()
				.filter(e -> e.active() && e.type() == AccountEvent.Type.PAYMENT).map(AccountEvent::eventDate)
				.toList();
		LocalDate limitationStart = acq != null ? acq.limitationStartDate()
				: a.acceleratedOn() != null ? a.acceleratedOn()
						: a.maturityDate() != null && a.maturityDate().isBefore(today) ? a.maturityDate() : null;
		List<LocalDate> interruptions = limitationStart == null ? List.of()
				: paymentDates.stream().filter(d -> d.isAfter(limitationStart)).toList();
		LocalDate limitation = LimitationPeriod.completionDate(limitationStart, interruptions,
				acq == null ? null : acq.judgmentConfirmedOn());
		Acquisition acquisition = acq == null ? null
				: new Acquisition(acq.acquiredOn(), acq.acquisitionPrice().won(), acq.seller(),
						acq.appliedRate().percent(), acq.interestMode(),
						Money.won(a.totalPaid()).percentOf(acq.acquisitionPrice()),
						paymentDates.isEmpty() ? null
								: java.time.temporal.ChronoUnit.DAYS.between(
										paymentDates.stream().max(LocalDate::compareTo).orElseThrow(), today));
		return new Detail(a.getId(), a.accountNo(), customers.get(a.customerId()), product.code(), product.name(),
				a.repaymentMethod().name(), a.origin().name(), a.status().name(), a.initialPrincipal().won(),
				a.disbursedOn(), a.maturityDate(), a.termMonths(), a.graceMonths(), currentRate,
				currentRate == null ? null : new InterestRate(currentRate).lateRate().percent(), currentDueDay,
				a.installmentAmount(), a.principalBalance(), a.suspenseBalance(), a.costBalance(),
				a.preAcqInterest(), a.unpaidNormalInterest() + a.accruedNormalInterest(),
				a.unpaidLateInterest() + a.accruedLateInterest(), a.payoffAmount(),
				prepaymentFee(a, Money.won(a.principalBalance()), today).won(), a.delinquencyDays(),
				a.delinquentSince(), a.nextDueDate(), a.acceleratedOn(), a.paidOffOn(), a.lastSettledOn(), a.asOf(),
				today, a.virtualAccountNo(), a.totalPaid(), a.refundPending(), a.assignedStaffId(), limitation,
				acquisition);
	}

	@GetMapping("/{id}/schedule")
	@PreAuthorize("hasAuthority('loan.read')")
	@Transactional(readOnly = true)
	Schedule schedule(@PathVariable long id) {
		LoanAccount a = visible(id);
		List<InstallmentDto> current = new ArrayList<>();
		Map<Integer, List<InstallmentDto>> previous = new LinkedHashMap<>();
		for (InstallmentRow r : installments.findByAccountIdOrderByScheduleVersionAscSeqAsc(id)) {
			InstallmentDto dto = new InstallmentDto(r.scheduleVersion(), r.seq(), r.periodStart(), r.dueDate(),
					r.graceEnd(), r.principalDue(), r.plannedInterest(), r.principalDue() + r.plannedInterest(),
					r.principalPaid(), r.status());
			if (r.scheduleVersion() == a.scheduleVersion()) {
				current.add(dto);
			} else {
				previous.computeIfAbsent(r.scheduleVersion(), v -> new ArrayList<>()).add(dto);
			}
		}
		return new Schedule(a.scheduleVersion(), current, previous);
	}

	@GetMapping("/{id}/ledger")
	@PreAuthorize("hasAuthority('loan.read')")
	@Transactional(readOnly = true)
	List<LedgerDto> ledger(@PathVariable long id) {
		visible(id);
		List<LedgerEntry> entries = ledger.findByAccountIdOrderById(id);
		Set<Long> reversed = new HashSet<>();
		entries.forEach(e -> {
			if (e.reversalOfId() != null) {
				reversed.add(e.reversalOfId());
			}
		});
		return entries.stream().map(e -> new LedgerDto(e.getId(), e.type(), e.valueDate(), e.businessDate(),
				e.postedAt(), e.sourceRef(), e.amount(), e.principal(), e.normalInterest(), e.lateInterest(),
				e.cost(), e.fee(), e.preAcqInterest(), e.suspense(), e.refund(), e.normalAccrued(), e.lateAccrued(),
				e.principalBalanceAfter(), e.paymentId(), e.correctionId(), e.reversalOfId(),
				reversed.contains(e.getId()),
				e.allocations().stream()
						.map(x -> new AllocationDto(x.installmentSeq(), x.component(), x.amount())).toList()))
				.toList();
	}

	@GetMapping("/{id}/history")
	@PreAuthorize("hasAuthority('loan.read')")
	@Transactional(readOnly = true)
	History history(@PathVariable long id) {
		visible(id);
		return new History(
				rateTerms.findByAccountIdOrderByValidFrom(id).stream().map(t -> new RateTermDto(t.rate().percent(),
						t.validFrom(), t.validTo(), t.reason(), t.recordedAt())).toList(),
				dueDayTerms.findByAccountIdOrderByValidFrom(id).stream().map(t -> new DueDayTermDto(t.dueDay(),
						t.validFrom(), t.validTo(), t.requestedOn(), t.reason(), t.recordedAt())).toList(),
				notices.findByAccountIdOrderById(id).stream().map(n -> new NoticeDto(n.reasonDate(), n.sentOn(),
						n.deemedArrivalOn(), n.actualArrivalOn(), n.accelerationDate(), n.status(), n.closedOn()))
						.toList(),
				events.findByAccountIdOrderById(id).stream().map(e -> new EventDto(e.getId(), e.ref(),
						e.type().name(), e.eventDate(), e.amount(), e.newDueDay(), e.paymentId(), e.status(),
						e.cancelledOn(), e.reason(), e.getCreatedBy(), e.getCreatedAt())).toList());
	}

	/** 업무일자 기준 중도상환·완제 견적 (R7·R8). principal을 비우면 전액 */
	@GetMapping("/{id}/quote")
	@PreAuthorize("hasAuthority('loan.read')")
	@Transactional(readOnly = true)
	Quote quote(@PathVariable long id, @RequestParam(required = false) Long principal) {
		LoanAccount a = visible(id);
		LocalDate today = businessDates.today();
		SimulationResult r = replayer.simulate(a, replayer.terms(a), List.of(), today);
		if (r.status() == AccountStatus.PAID_OFF) {
			throw new BusinessException("ACCOUNT_CLOSED", "완제된 계약입니다");
		}
		long overdue = r.installments().stream().filter(i -> !i.dueDate().isAfter(today))
				.mapToLong(i -> i.principalDue().minus(i.principalPaid()).won()).sum();
		boolean dueAll = r.status() == AccountStatus.ACCELERATED || a.repaymentMethod().settlesOnPayment()
				|| (r.maturityDate() != null && today.isAfter(r.maturityDate()));
		boolean full = principal == null || principal >= r.principalBalance().won() || dueAll;
		Money p = full ? r.principalBalance() : Money.won(principal);
		Money fee = dueAll ? Money.ZERO : prepaymentFee(a, r, p, today);
		long interest = r.outstandingNormalInterest().won();
		long late = r.outstandingLateInterest().won();
		long principalPart = full ? p.won() + r.preAcqInterest().won() : p.won() + overdue;
		long total = Math.max(0, r.cost().won() + late + interest + principalPart + fee.won() - r.suspense().won());
		return new Quote(today, r.cost().won(), late, interest, full ? 0 : overdue, p.won(), fee.won(),
				r.suspense().won(), total, full);
	}

	// ------------------------------------------------------------------ 변경

	@PostMapping
	@PreAuthorize("hasAuthority('loan.create')")
	AccountRef open(@Valid @RequestBody OpenRequest body) {
		return service.open(new LoanAccounts.OpenCommand(body.customerId(), body.productCode(),
				Money.won(body.principal()), new InterestRate(body.rate()), body.termMonths(), body.graceMonths(),
				body.dueDay(), body.disbursedOn() == null ? businessDates.today() : body.disbursedOn(),
				body.assignedStaffId(), null));
	}

	@PostMapping("/acquisitions")
	@PreAuthorize("hasAuthority('loan.create')")
	AcquireResponse acquire(@Valid @RequestBody AcquireRequest body) {
		List<String> warnings = new ArrayList<>();
		LocalDate completion = LimitationPeriod.completionDate(body.limitationStartDate(), List.of(),
				body.judgmentConfirmedOn());
		if (completion != null && !completion.isAfter(body.acquiredOn())) {
			warnings.add("양수 시점에 소멸시효(" + completion + ")가 이미 완성된 채권입니다. 추심 연락을 차단하세요");
		}
		AccountRef ref = service.acquire(new LoanAccounts.AcquireCommand(body.customerId(), body.acquiredOn(),
				Money.won(body.acquisitionPrice()), body.seller(), body.originalContractDate(),
				new InterestRate(body.originalRate()),
				body.originalLateRate() == null ? null : new InterestRate(body.originalLateRate()),
				Money.won(body.principal()), Money.won(body.preAcqInterest()), Money.won(body.cost()),
				body.interestCharged(), body.originalAccelerationDate(), body.limitationStartDate(),
				body.judgmentConfirmedOn(), body.assignedStaffId()));
		return new AcquireResponse(ref, warnings);
	}

	@PostMapping("/{id}/rate-changes")
	@PreAuthorize("hasAuthority('loan.term.change')")
	AccountRef changeRate(@PathVariable long id, @Valid @RequestBody RateChangeRequest body) {
		visibleInTx(id);
		return service.changeRate(id, new InterestRate(body.rate()), body.validFrom(), body.reason());
	}

	@PostMapping("/{id}/due-day-changes")
	@PreAuthorize("hasAuthority('loan.term.change')")
	AccountRef changeDueDay(@PathVariable long id, @Valid @RequestBody DueDayChangeRequest body) {
		visibleInTx(id);
		return service.changeDueDay(id, body.dueDay(), body.reason());
	}

	@PostMapping("/{id}/costs")
	@PreAuthorize("hasAuthority('payment.register')")
	AccountRef chargeCost(@PathVariable long id, @Valid @RequestBody CostRequest body) {
		visibleInTx(id);
		return service.chargeCost(id, Money.won(body.amount()), body.reason());
	}

	// ------------------------------------------------------------------

	private void visibleInTx(long id) {
		visible(id);
	}

	private LoanAccount visible(long id) {
		LoanAccount a = accounts.findById(id)
				.orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "계약이 없습니다"));
		if (!staff.loanScope().allows(a.assignedStaffId())) {
			throw new BusinessException("FORBIDDEN", "담당 범위 밖의 계약입니다");
		}
		return a;
	}

	private Money prepaymentFee(LoanAccount a, Money principal, LocalDate today) {
		if (a.maturityDate() == null || !today.isBefore(a.maturityDate())
				|| a.repaymentMethod() == RepaymentMethod.ACQUIRED) {
			return Money.ZERO;
		}
		return ScheduleMath.prepaymentFee(principal, a.prepaymentFeeRate(), a.disbursedOn(), a.maturityDate(),
				today);
	}

	private static Money prepaymentFee(LoanAccount a, SimulationResult r, Money principal, LocalDate today) {
		if (r.maturityDate() == null || !today.isBefore(r.maturityDate())) {
			return Money.ZERO;
		}
		return ScheduleMath.prepaymentFee(principal, a.prepaymentFeeRate(), a.disbursedOn(), r.maturityDate(),
				today);
	}

	private static InterestRate currentRate(List<RateTerm> rates, LocalDate today) {
		InterestRate rate = rates.getFirst().rate();
		for (RateTerm t : rates) {
			if (!t.validFrom().isAfter(today)) {
				rate = t.rate();
			}
		}
		return rate;
	}

	private static Set<Long> nonEmpty(Set<Long> ids) {
		return ids == null || ids.isEmpty() ? Set.of(-1L) : ids;
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s;
	}
}
