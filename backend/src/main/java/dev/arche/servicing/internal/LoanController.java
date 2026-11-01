package dev.arche.servicing.internal;

import org.springframework.web.bind.annotation.ModelAttribute;

import dev.arche.servicing.LoanFilter;

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
import dev.arche.servicing.LimitationInfo;
import dev.arche.servicing.LoanAccounts;
import dev.arche.servicing.engine.AccountStatus;
import dev.arche.servicing.engine.LimitationPeriod;
import dev.arche.servicing.engine.LoanEvent;
import dev.arche.servicing.engine.ScheduleMath;
import dev.arche.servicing.engine.SimulationResult;
import dev.arche.staff.DataScope;
import dev.arche.staff.AccessLogger;
import dev.arche.staff.CurrentStaff;
import dev.arche.staff.Permission;
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
			Long assignedStaffId, String assignedStaffName, long initialPrincipal, LocalDate disbursedOn) {
	}

	/**
	 * 계약 목록 검색 조건 (ADR 0011). 쿼리스트링 이름 그대로 받는다. q는 예전 검색창(계약번호 또는 주민등록번호).
	 * sort는 LoanQuery.SORT_COLUMNS의 키, dir은 asc/desc
	 */
	record ListQuery(String status, String productCode, Long assignedStaffId, Boolean unassigned, Long principalMin,
			Long principalMax, Long payoffMin, Long payoffMax, Integer delinquencyMin, Integer delinquencyMax,
			LocalDate nextDueFrom, LocalDate nextDueTo, LocalDate disbursedFrom, LocalDate disbursedTo,
			String keywordType, String keyword, String q, Integer page, Integer size, String sort, String dir) {

		LoanFilter filter() {
			boolean legacy = (keyword == null || keyword.isBlank()) && q != null && !q.isBlank();
			return new LoanFilter(status, productCode, assignedStaffId, Boolean.TRUE.equals(unassigned), principalMin,
					principalMax, payoffMin, payoffMax, delinquencyMin, delinquencyMax, nextDueFrom, nextDueTo,
					disbursedFrom, disbursedTo, legacy ? "AUTO" : keywordType, legacy ? q : keyword);
		}

		@Override
		public String toString() {
			return "ListQuery[" + filter() + "]";
		}
	}

	record Assignee(long id, String name, String roleLabel, String team) {
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
			Long assignedStaffId, LocalDate limitationCompletionDate, Acquisition acquisition,
			String assignedStaffName, String assignedStaffTeam) {
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
			OffsetDateTime createdAt, Long waiveLate, Long waiveNormal, Long waivePrincipal, Integer installments) {
	}

	record WorkoutRequest(long waiveLate, long waiveNormal, long waivePrincipal, int installments, int dueDay,
			@NotBlank String reason) {
	}

	record WorkoutQuote(LocalDate date, long cost, long lateInterest, long normalInterest, long principal,
			long waiveLate, long waiveNormal, long waivePrincipal, long requiredPayment, long paidToday,
			long remainingPayment, long newPrincipal, Long installmentAmount, List<InstallmentDto> schedulePreview) {
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
	private final LimitationService limitationService;
	private final Customers customers;
	private final ProductCatalog products;
	private final StaffDirectory staff;
	private final AccessLogger accessLog;
	private final BusinessDates businessDates;
	private final LoanQuery loanQuery;

	LoanController(LoanAccountRepository accounts, RateTermRepository rateTerms, DueDayTermRepository dueDayTerms,
			InstallmentRepository installments, LedgerEntryRepository ledger, AccelerationNoticeRepository notices,
			AccountEventRepository events, AcquisitionRepository acquisitions, AccountReplayer replayer,
			LoanAccountService service, LimitationService limitationService, Customers customers, AccessLogger accessLog, ProductCatalog products, StaffDirectory staff,
			BusinessDates businessDates, LoanQuery loanQuery) {
		this.loanQuery = loanQuery;
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
		this.limitationService = limitationService;
		this.customers = customers;
		this.products = products;
		this.staff = staff;
		this.accessLog = accessLog;
		this.businessDates = businessDates;
	}

	// ------------------------------------------------------------------ 목록

	@GetMapping
	@PreAuthorize("hasAuthority('loan.read')")
	@Transactional(readOnly = true)
	PageResponse<ListItem> list(@ModelAttribute ListQuery query) {
		LoanFilter filter = query.filter();
		if (filter.identifierSearch()) {
			// 주민등록번호·휴대폰으로 찾은 사실만 남기고 입력값은 남기지 않는다
			accessLog.log("LOAN_LIST_IDENTIFIER_SEARCH", "LOAN_LIST", filter.keywordType(), null);
		}
		int page = query.page() == null ? 0 : Math.max(query.page(), 0);
		int size = query.size() == null ? 50 : query.size();
		LoanQuery.Page result = loanQuery.page(filter, staff.loanScope(), page, size, query.sort(),
				"desc".equalsIgnoreCase(query.dir()));
		int pageSize = LoanQuery.PAGE_SIZES.contains(size) ? size : 50;
		Map<Long, String> names = new java.util.HashMap<>();
		List<ListItem> items = result.rows().stream().map(r -> new ListItem(r.id(), r.accountNo(),
				customers.get(r.customerId()).name(), r.productCode(), r.status(), r.principalBalance(),
				r.payoffAmount(), r.delinquencyDays(), r.nextDueDate(), r.assignedStaffId(),
				r.assignedStaffId() == null ? null
						: names.computeIfAbsent(r.assignedStaffId(),
								id -> staff.byId(id).map(dev.arche.staff.CurrentStaff::name).orElse("#" + id)),
				r.initialPrincipal(), r.disbursedOn())).toList();
		return new PageResponse<>(items, page, pageSize, result.total(),
				(int) ((result.total() + pageSize - 1) / pageSize));
	}

	/** 담당자 검색 목록: 계약을 맡을 수 있는 재직 직원(채권담당·채권팀장) */
	@GetMapping("/assignees")
	@PreAuthorize("hasAuthority('loan.read')")
	List<Assignee> assignees() {
		Map<Long, String> teams = staff.teamNames();
		return staff.active().stream()
				.filter(s -> s.role() == dev.arche.staff.Role.COLLECTOR || s.role() == dev.arche.staff.Role.COLLECTION_LEAD)
				.map(s -> new Assignee(s.id(), s.name(), s.role().label(), s.teamId() == null ? null : teams.get(s.teamId())))
				.toList();
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

	// ------------------------------------------------------------------ 통합 검색 (상단 검색창)

	record SearchHit(long id, String accountNo, String customerName, String residentNoMasked, String phoneMasked,
			String productCode, String status, long principalBalance, String matchedBy) {
	}

	static final int SEARCH_LIMIT = 20;

	/**
	 * 계약번호(L로 시작하거나 숫자) 부분 일치, 주민등록번호·휴대폰번호 블라인드 인덱스 일치, 고객 이름 부분 일치.
	 * 조회 범위(담당 건 / 팀 전체) 안의 계약만, 최대 20건. 주민등록번호·휴대폰 검색은 접근 기록에 남긴다(입력값은 남기지 않음)
	 */
	@GetMapping("/search")
	@PreAuthorize("hasAuthority('loan.read')")
	@Transactional(readOnly = true)
	List<SearchHit> search(@RequestParam String q) {
		String query = q == null ? "" : q.trim();
		if (query.isEmpty()) {
			return List.of();
		}
		DataScope scope = staff.loanScope();
		Customers.SearchKind kind = Customers.kindOf(query);
		Map<Long, SearchHit> hits = new LinkedHashMap<>();
		if (kind == Customers.SearchKind.NAME && query.matches("[Ll]?[0-9]+")) {
			for (LoanAccount a : accounts.findTop50ByAccountNoContainingOrderByIdDesc(query.toUpperCase())) {
				addHit(hits, scope, a, customers.get(a.customerId()), "계약번호");
			}
		} else {
			if (kind != Customers.SearchKind.NAME) {
				accessLog.log("CUSTOMER_SEARCH", "CUSTOMER", null,
						kind == Customers.SearchKind.RESIDENT_NO ? "주민등록번호 검색" : "휴대폰번호 검색");
			}
			Map<Long, CustomerView> found = new LinkedHashMap<>();
			customers.search(query).forEach(c -> found.put(c.id(), c));
			String matchedBy = switch (kind) {
				case RESIDENT_NO -> "주민등록번호";
				case PHONE -> "휴대폰번호";
				case NAME -> "이름";
			};
			if (!found.isEmpty()) {
				for (LoanAccount a : accounts.findTop50ByCustomerIdInOrderByIdDesc(found.keySet())) {
					addHit(hits, scope, a, found.get(a.customerId()), matchedBy);
				}
			}
		}
		return hits.values().stream().limit(SEARCH_LIMIT).toList();
	}

	private static void addHit(Map<Long, SearchHit> hits, DataScope scope, LoanAccount a, CustomerView c,
			String matchedBy) {
		if (scope.allows(a.assignedStaffId())) {
			hits.putIfAbsent(a.getId(), new SearchHit(a.getId(), a.accountNo(), c.name(), c.residentNoMasked(),
					c.phoneMasked(), a.productCode(), a.status().name(), a.principalBalance(), matchedBy));
		}
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
		LocalDate limitation = limitationService.of(a).completionDate();
		Acquisition acquisition = acq == null ? null
				: new Acquisition(acq.acquiredOn(), acq.acquisitionPrice().won(), acq.seller(),
						acq.appliedRate().percent(), acq.interestMode(),
						Money.won(a.totalPaid()).percentOf(acq.acquisitionPrice()),
						paymentDates.isEmpty() ? null
								: java.time.temporal.ChronoUnit.DAYS.between(
										paymentDates.stream().max(LocalDate::compareTo).orElseThrow(), today));
		// 담당자: 퇴사자여도 이름은 보여 준다(퇴사 시 이관했다면 이미 후임으로 바뀌어 있음)
		var assignee = a.assignedStaffId() == null ? java.util.Optional.<dev.arche.staff.CurrentStaff>empty()
				: staff.byId(a.assignedStaffId());
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
				acquisition, assignee.map(dev.arche.staff.CurrentStaff::name).orElse(null),
				assignee.map(dev.arche.staff.CurrentStaff::teamId).map(staff.teamNames()::get).orElse(null));
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
						e.cancelledOn(), e.reason(), e.getCreatedBy(), e.getCreatedAt(), e.waiveLate(), e.waiveNormal(),
						e.waivePrincipal(), e.installments())).toList());
	}

	/** 업무일자 기준 중도상환·완제 견적 (R7·R8). principal을 비우면 전액 */
	@GetMapping("/{id}/quote")
	@PreAuthorize("hasAuthority('loan.read')")
	@Transactional(readOnly = true)
	Quote quote(@PathVariable long id, @RequestParam(required = false) Long principal) {
		LoanAccount a = visible(id);
		LocalDate today = businessDates.today();
		SimulationResult r = replayer.simulate(a, replayer.terms(a), List.of(), today);
		if (r.status() == AccountStatus.PAID_OFF || r.status() == AccountStatus.SOLD) {
			throw new BusinessException("ACCOUNT_CLOSED", "완제·매각된 계약입니다");
		}
		long overdue = r.installments().stream().filter(i -> !i.dueDate().isAfter(today))
				.mapToLong(i -> i.principalDue().minus(i.principalPaid()).won()).sum();
		boolean dueAll = r.status() == AccountStatus.ACCELERATED || r.status() == AccountStatus.CHARGED_OFF
				|| a.repaymentMethod().settlesOnPayment()
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

	// ------------------------------------------------------------------ 채무조정 (R9·R23, G23)

	/** 채무조정 견적: 감면 후 체결일에 받아야 할 금액과 새 스케줄 미리보기 */
	@GetMapping("/{id}/workout-quote")
	@PreAuthorize("hasAnyAuthority('workout.waive.late', 'workout.waive.normal', 'workout.waive.principal')")
	@Transactional(readOnly = true)
	WorkoutQuote workoutQuote(@PathVariable long id, @RequestParam(defaultValue = "0") long waiveLate,
			@RequestParam(defaultValue = "0") long waiveNormal, @RequestParam(defaultValue = "0") long waivePrincipal,
			@RequestParam(defaultValue = "24") int installments, @RequestParam(defaultValue = "25") int dueDay) {
		LoanAccount a = visible(id);
		LoanAccountService.validateWorkout(new LoanAccountService.WorkoutCommand(Money.won(waiveLate),
				Money.won(waiveNormal), Money.won(waivePrincipal), installments, dueDay, "견적"));
		LocalDate today = businessDates.today();
		var terms = replayer.terms(a);
		SimulationResult now = replayer.simulate(a, terms, List.of(), today);
		var workout = new LoanEvent.Workout("QUOTE", today, Money.won(waiveLate), Money.won(waiveNormal),
				Money.won(waivePrincipal), installments, dueDay);
		var planned = replayer.simulate(a, terms, List.of(workout), today).workouts().getLast();
		long paidToday = events.findByAccountIdOrderById(id).stream()
				.filter(e -> e.active() && e.type() == AccountEvent.Type.PAYMENT && e.eventDate().equals(today))
				.mapToLong(AccountEvent::amount).sum();
		long remaining = Math.max(0, planned.requiredPayment().won() - paidToday);
		List<LoanEvent> preview = remaining > 0
				? List.of(workout, new LoanEvent.Payment("QUOTE-PAY", today, Money.won(remaining)))
				: List.of(workout);
		SimulationResult after = replayer.simulate(a, terms, preview, today);
		boolean concluded = after.workouts().getLast().concluded();
		return new WorkoutQuote(today, now.cost().won(), now.outstandingLateInterest().won(),
				now.outstandingNormalInterest().won(), now.principalBalance().won(), planned.waivedLate().won(),
				planned.waivedNormal().won(), planned.waivedPrincipal().won(), planned.requiredPayment().won(),
				paidToday, remaining, now.principalBalance().won() - planned.waivedPrincipal().won(),
				concluded && after.installmentAmount() != null ? after.installmentAmount().won() : null,
				concluded ? after.installments().stream().map(i -> new InstallmentDto(i.scheduleVersion(), i.seq(),
						i.periodStart(), i.dueDate(), i.graceEnd(), i.principalDue().won(), i.plannedInterest().won(),
						i.plannedPayment().won(), i.principalPaid().won(), i.status().name())).toList() : List.of());
	}

	/** 채무조정 체결. 감면 항목별 결재권: 연체이자 채권담당 / 정상이자 채권팀장 / 원금 본부장 (정책서 5장, 9.1) */
	@PostMapping("/{id}/workouts")
	@PreAuthorize("hasAnyAuthority('workout.waive.late', 'workout.waive.normal', 'workout.waive.principal')")
	AccountRef workout(@PathVariable long id, @Valid @RequestBody WorkoutRequest body) {
		visibleInTx(id);
		CurrentStaff me = staff.current();
		requireIf(body.waiveLate() > 0, me, Permission.WORKOUT_LATE_INTEREST, "연체이자");
		requireIf(body.waiveNormal() > 0, me, Permission.WORKOUT_NORMAL_INTEREST, "정상이자");
		requireIf(body.waivePrincipal() > 0, me, Permission.WORKOUT_PRINCIPAL, "원금");
		return service.workout(id, new LoanAccountService.WorkoutCommand(Money.won(body.waiveLate()),
				Money.won(body.waiveNormal()), Money.won(body.waivePrincipal()), body.installments(), body.dueDay(),
				body.reason()));
	}

	private static void requireIf(boolean needed, CurrentStaff me, Permission p, String what) {
		if (needed && !me.has(p)) {
			throw new BusinessException("FORBIDDEN", what + " 감면 결재 권한이 없습니다");
		}
	}

	// ------------------------------------------------------------------ 소멸시효 (G13)

	record LimitationEventRequest(@NotNull LimitationEvent.Type type, @NotNull LocalDate date, String memo) {
	}

	@GetMapping("/{id}/limitation")
	@PreAuthorize("hasAuthority('loan.read')")
	@Transactional(readOnly = true)
	LimitationInfo limitation(@PathVariable long id) {
		return limitationService.of(visible(id));
	}

	/** 지급명령 신청·압류·판결 확정 기록 (중단 → 다시 5년, 확정 → 10년) */
	@PostMapping("/{id}/limitation-events")
	@PreAuthorize("hasAuthority('collection.legal')")
	LimitationInfo recordLimitationEvent(@PathVariable long id, @Valid @RequestBody LimitationEventRequest body) {
		LoanAccount a = visible(id);
		if (body.date().isAfter(businessDates.today())) {
			throw new BusinessException("DATE_INVALID", "업무일자 이후 날짜는 기록할 수 없습니다");
		}
		limitationService.record(id, body.type(), body.date(), body.memo());
		return limitationService.of(a);
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
}
