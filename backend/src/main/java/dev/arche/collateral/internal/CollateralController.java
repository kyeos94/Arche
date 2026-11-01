package dev.arche.collateral.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.common.BusinessException;
import dev.arche.common.Money;
import dev.arche.common.crypto.BlindIndexer;
import dev.arche.customer.Customers;
import dev.arche.integration.CollateralValuationPort;
import dev.arche.servicing.AccountRef;
import dev.arche.servicing.LoanAccounts;
import dev.arche.staff.StaffDirectory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

interface CollateralRepository extends JpaRepository<Collateral, Long> {

	List<Collateral> findByAccountIdOrderById(long accountId);
}

interface CollateralValuationRepository extends JpaRepository<CollateralValuation, Long> {

	List<CollateralValuation> findByCollateralIdInOrderByIdDesc(List<Long> collateralIds);
}

interface GuarantorRepository extends JpaRepository<Guarantor, Long> {

	List<Guarantor> findByAccountIdOrderById(long accountId);

	boolean existsByAccountIdAndResidentNoBlindIndexAndStatus(long accountId, String bidx, LinkStatus status);
}

/**
 * 계약 상세의 담보·보증인 탭 API (guide 13.2). 조회는 계약 조회 범위, 등록·시세 조회·해지는 금리·약정일 변경 권한(채권팀장·본부장, R60).
 */
@RestController
@RequestMapping("/api/loans/{id}")
class CollateralController {

	record CollateralRequest(@NotNull CollateralType type, @NotBlank @Size(max = 200) String description,
			@Size(max = 60) String identifier, @Size(max = 20) String vehicleClass, Integer modelYear,
			@Size(max = 200) String address, @Positive BigDecimal areaM2, @PositiveOrZero long seniorClaim,
			@PositiveOrZero long lienAmount, Integer lienRank) {
	}

	record ValuationDto(long id, LocalDate valuedOn, long price, String source, String basis) {
	}

	record CollateralDto(long id, String type, String description, String identifier, String vehicleClass,
			Integer modelYear, String address, BigDecimal areaM2, long seniorClaim, long lienAmount, int lienRank,
			String status, LocalDate releasedOn, BigDecimal ratio, Long recognizedValue, boolean stale,
			ValuationDto latest, List<ValuationDto> history) {
	}

	record SummaryDto(long totalPrice, long totalSenior, long totalRecognized, long principalBalance,
			BigDecimal ltv, long margin, int valuedCount, int staleCount) {
		static SummaryDto of(CollateralMath.Summary s) {
			return new SummaryDto(s.totalPrice().won(), s.totalSenior().won(), s.totalRecognized().won(),
					s.principalBalance().won(), s.ltv(), s.margin().won(), s.valuedCount(), s.staleCount());
		}
	}

	record Overview(LocalDate businessDate, List<CollateralDto> collaterals, SummaryDto summary) {
	}

	record GuarantorRequest(@NotBlank @Size(max = 50) String name,
			@NotBlank @Pattern(regexp = "\\d{6}-?\\d{7}") String residentNo, @Size(max = 20) String phone,
			@NotBlank @Size(max = 30) String relation, @Positive long guaranteeLimit, @NotNull LocalDate startDate,
			LocalDate endDate) {

		@Override
		public String toString() {
			return "GuarantorRequest[" + relation + "]";
		}
	}

	record GuarantorDto(long id, String name, String residentNoMasked, String phoneMasked, String relation,
			long guaranteeLimit, LocalDate startDate, LocalDate endDate, String status, LocalDate releasedOn) {
		static GuarantorDto of(Guarantor g) {
			return new GuarantorDto(g.getId(), g.name(), g.residentNoMasked(), g.phoneMasked(), g.relation(),
					g.guaranteeLimit().won(), g.startDate(), g.endDate(), g.status().name(), g.releasedOn());
		}
	}

	private final CollateralRepository collaterals;
	private final CollateralValuationRepository valuations;
	private final GuarantorRepository guarantors;
	private final CollateralValuationPort valuationPort;
	private final LoanAccounts accounts;
	private final Customers customers;
	private final StaffDirectory staff;
	private final BlindIndexer indexer;
	private final BusinessDates businessDates;
	private final Clock clock;

	CollateralController(CollateralRepository collaterals, CollateralValuationRepository valuations,
			GuarantorRepository guarantors, CollateralValuationPort valuationPort, LoanAccounts accounts,
			Customers customers, StaffDirectory staff, BlindIndexer indexer, BusinessDates businessDates, Clock clock) {
		this.collaterals = collaterals;
		this.valuations = valuations;
		this.guarantors = guarantors;
		this.valuationPort = valuationPort;
		this.accounts = accounts;
		this.customers = customers;
		this.staff = staff;
		this.indexer = indexer;
		this.businessDates = businessDates;
		this.clock = clock;
	}

	// ------------------------------------------------------------------ 담보

	@GetMapping("/collaterals")
	@PreAuthorize("hasAuthority('loan.read')")
	@Transactional(readOnly = true)
	Overview collaterals(@PathVariable long id) {
		AccountRef account = visible(id);
		LocalDate today = businessDates.today();
		List<Collateral> list = collaterals.findByAccountIdOrderById(id);
		Map<Long, List<CollateralValuation>> history = list.isEmpty() ? Map.of()
				: valuations.findByCollateralIdInOrderByIdDesc(list.stream().map(Collateral::getId).toList()).stream()
						.collect(Collectors.groupingBy(CollateralValuation::collateralId));
		List<CollateralDto> dtos = list.stream().map(c -> dto(c, history.getOrDefault(c.getId(), List.of()), today))
				.toList();
		List<CollateralMath.Line> lines = list.stream().filter(c -> c.status() == LinkStatus.ACTIVE).map(c -> {
			CollateralValuation v = history.getOrDefault(c.getId(), List.of()).stream().findFirst().orElse(null);
			return new CollateralMath.Line(c.type(), v == null ? null : v.price(), v == null ? null : v.valuedOn(),
					c.seniorClaim());
		}).toList();
		return new Overview(today, dtos,
				SummaryDto.of(CollateralMath.summarize(lines, Money.won(account.principalBalance()), today)));
	}

	@PostMapping("/collaterals")
	@PreAuthorize("hasAuthority('loan.term.change')")
	@Transactional
	CollateralDto register(@PathVariable long id, @Valid @RequestBody CollateralRequest r) {
		AccountRef account = visible(id);
		if (account.paidOffOn() != null) {
			throw new BusinessException("ACCOUNT_CLOSED", "완제된 계약입니다");
		}
		if (r.type() == CollateralType.VEHICLE && (r.vehicleClass() == null || r.modelYear() == null)) {
			throw new BusinessException("COLLATERAL_INVALID", "차량은 차종 구분과 연식이 필요합니다");
		}
		if (r.type() == CollateralType.REAL_ESTATE && (r.address() == null || r.address().isBlank() || r.areaM2() == null)) {
			throw new BusinessException("COLLATERAL_INVALID", "부동산은 소재지와 전용면적이 필요합니다");
		}
		boolean vehicle = r.type() == CollateralType.VEHICLE;
		Collateral c = collaterals.save(new Collateral(id, r.type(), r.description(), blank(r.identifier()),
				vehicle ? r.vehicleClass() : null, vehicle ? r.modelYear() : null, vehicle ? null : r.address(),
				vehicle ? null : r.areaM2(), Money.won(r.seniorClaim()), Money.won(r.lienAmount()),
				r.lienRank() == null ? 1 : r.lienRank()));
		return dto(c, List.of(), businessDates.today());
	}

	/** 시세 조회 (CollateralValuationPort) → 이력에 추가 */
	@PostMapping("/collaterals/{collateralId}/valuations")
	@PreAuthorize("hasAuthority('loan.term.change')")
	@Transactional
	ValuationDto valuate(@PathVariable long id, @PathVariable long collateralId) {
		visible(id);
		Collateral c = collateral(id, collateralId);
		if (c.status() != LinkStatus.ACTIVE) {
			throw new BusinessException("COLLATERAL_RELEASED", "해지된 담보입니다");
		}
		LocalDate today = businessDates.today();
		Optional<CollateralValuationPort.Valuation> v = c.type() == CollateralType.VEHICLE
				? valuationPort.vehicle(c.vehicleClass(), c.modelYear(), today)
				: valuationPort.realEstate(c.address(), c.areaM2(), today);
		CollateralValuationPort.Valuation found = v.orElseThrow(
				() -> new BusinessException("VALUATION_NOT_FOUND", "시세표에 없는 담보입니다 (차종 구분·연식 또는 소재지를 확인하세요)"));
		CollateralValuation saved = valuations.save(new CollateralValuation(c.getId(), found.asOf(), found.price(),
				found.source(), found.basis(), OffsetDateTime.now(clock), staff.current().loginId()));
		return dto(saved);
	}

	/** 담보 해지: 완제 후에만 (R60) */
	@PostMapping("/collaterals/{collateralId}/release")
	@PreAuthorize("hasAuthority('loan.term.change')")
	@Transactional
	CollateralDto release(@PathVariable long id, @PathVariable long collateralId) {
		AccountRef account = visible(id);
		Collateral c = collateral(id, collateralId);
		if (account.paidOffOn() == null) {
			throw new BusinessException("RELEASE_NOT_ALLOWED", "담보 해지는 완제 후에만 할 수 있습니다");
		}
		if (c.status() == LinkStatus.RELEASED) {
			throw new BusinessException("COLLATERAL_RELEASED", "이미 해지된 담보입니다");
		}
		c.release(businessDates.today());
		return dto(c, List.of(), businessDates.today());
	}

	// ------------------------------------------------------------------ 보증인

	@GetMapping("/guarantors")
	@PreAuthorize("hasAuthority('loan.read')")
	@Transactional(readOnly = true)
	List<GuarantorDto> guarantors(@PathVariable long id) {
		visible(id);
		return guarantors.findByAccountIdOrderById(id).stream().map(GuarantorDto::of).toList();
	}

	@PostMapping("/guarantors")
	@PreAuthorize("hasAuthority('loan.term.change')")
	@Transactional
	GuarantorDto registerGuarantor(@PathVariable long id, @Valid @RequestBody GuarantorRequest r) {
		AccountRef account = visible(id);
		if (account.paidOffOn() != null) {
			throw new BusinessException("ACCOUNT_CLOSED", "완제된 계약입니다");
		}
		if (r.endDate() != null && r.endDate().isBefore(r.startDate())) {
			throw new BusinessException("GUARANTOR_INVALID", "보증 종료일이 시작일보다 이릅니다");
		}
		long borrower = accounts.messagingView(id).customerId();
		if (customers.findByResidentNo(r.residentNo()).map(c -> c.id() == borrower).orElse(false)) {
			throw new BusinessException("GUARANTOR_INVALID", "채무자 본인은 보증인이 될 수 없습니다");
		}
		String bidx = indexer.index(r.residentNo());
		if (guarantors.existsByAccountIdAndResidentNoBlindIndexAndStatus(id, bidx, LinkStatus.ACTIVE)) {
			throw new BusinessException("DUPLICATE", "이미 등록된 보증인입니다");
		}
		return GuarantorDto.of(guarantors.save(new Guarantor(id, r.name(), r.residentNo(), bidx, blank(r.phone()),
				r.relation(), Money.won(r.guaranteeLimit()), r.startDate(), r.endDate())));
	}

	/** 보증 해지: 완제 후 또는 보증 종료일이 지난 뒤 (R60) */
	@PostMapping("/guarantors/{guarantorId}/release")
	@PreAuthorize("hasAuthority('loan.term.change')")
	@Transactional
	GuarantorDto releaseGuarantor(@PathVariable long id, @PathVariable long guarantorId) {
		AccountRef account = visible(id);
		Guarantor g = guarantors.findById(guarantorId).filter(x -> x.accountId() == id)
				.orElseThrow(() -> new BusinessException("GUARANTOR_NOT_FOUND", "보증인이 없습니다"));
		LocalDate today = businessDates.today();
		boolean expired = g.endDate() != null && g.endDate().isBefore(today);
		if (account.paidOffOn() == null && !expired) {
			throw new BusinessException("RELEASE_NOT_ALLOWED", "보증 해지는 완제 후 또는 보증 종료일이 지난 뒤에만 할 수 있습니다");
		}
		if (g.status() == LinkStatus.RELEASED) {
			throw new BusinessException("GUARANTOR_RELEASED", "이미 해지된 보증입니다");
		}
		g.release(today);
		return GuarantorDto.of(g);
	}

	// ------------------------------------------------------------------ 공통

	private AccountRef visible(long id) {
		AccountRef a = accounts.get(id);
		if (!staff.loanScope().allows(a.assignedStaffId())) {
			throw new BusinessException("FORBIDDEN", "담당 범위 밖의 계약입니다");
		}
		return a;
	}

	private Collateral collateral(long accountId, long collateralId) {
		return collaterals.findById(collateralId).filter(c -> c.accountId() == accountId)
				.orElseThrow(() -> new BusinessException("COLLATERAL_NOT_FOUND", "담보가 없습니다"));
	}

	private static CollateralDto dto(Collateral c, List<CollateralValuation> history, LocalDate today) {
		List<CollateralValuation> sorted = history.stream()
				.sorted(Comparator.comparing(CollateralValuation::getId).reversed()).toList();
		CollateralValuation latest = sorted.isEmpty() ? null : sorted.get(0);
		Long recognized = latest == null ? null
				: CollateralMath.recognized(c.type(), latest.price(), c.seniorClaim()).won();
		return new CollateralDto(c.getId(), c.type().name(), c.description(), c.identifier(), c.vehicleClass(),
				c.modelYear(), c.address(), c.areaM2(), c.seniorClaim().won(), c.lienAmount().won(), c.lienRank(),
				c.status().name(), c.releasedOn(), CollateralMath.ratio(c.type()), recognized,
				CollateralMath.stale(latest == null ? null : latest.valuedOn(), today),
				latest == null ? null : dto(latest), sorted.stream().map(CollateralController::dto).toList());
	}

	private static ValuationDto dto(CollateralValuation v) {
		return new ValuationDto(v.getId(), v.valuedOn(), v.price().won(), v.source(), v.basis());
	}

	private static String blank(String s) {
		return s == null || s.isBlank() ? null : s.strip();
	}
}
