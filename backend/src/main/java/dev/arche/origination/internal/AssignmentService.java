package dev.arche.origination.internal;

import dev.arche.common.BusinessException;
import dev.arche.staff.CurrentStaff;
import dev.arche.staff.Role;
import dev.arche.staff.StaffDirectory;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 심사자 배정 (정책서 7장, R55). 후보 = 담당 상품이 맞고 배정 가능한 심사역. 고르는 방법은 {@link AssignmentStrategy}.
 * 동시에 두 건이 같은 사람의 "미처리 건수"를 읽고 몰리는 것을 막기 위해 배정은 트랜잭션 단위 advisory lock으로 직렬화한다.
 */
@Service
class AssignmentService {

	/** pg_advisory_xact_lock 키 (배정 전용) */
	static final long LOCK_KEY = 7_001_001L;

	/** 화면용 심사자 현황 */
	record UnderwriterView(long staffId, String loginId, String name, String role, List<String> productCodes,
			boolean allProducts, boolean available, int pending, OffsetDateTime lastAssignedAt) {

		boolean covers(String productCode) {
			return allProducts || productCodes.contains(productCode);
		}
	}

	private final AssignmentStrategy strategy;
	private final UnderwriterProfileRepository profiles;
	private final StaffDirectory staff;
	private final JdbcClient jdbc;
	private final Clock clock;

	AssignmentService(@Value("${arche.origination.assignment-strategy:least-loaded}") String strategy,
			UnderwriterProfileRepository profiles, StaffDirectory staff, JdbcClient jdbc, Clock clock) {
		this.strategy = AssignmentStrategy.of(strategy);
		this.profiles = profiles;
		this.staff = staff;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	String strategyCode() {
		return strategy.code();
	}

	/**
	 * 자동 배정: 심사역 중 전략으로 1명. 후보가 없으면 empty (예외를 던지면 바깥 트랜잭션까지 롤백 표시되므로 값으로 알린다)
	 */
	@Transactional
	Optional<CurrentStaff> assignAuto(LoanApplication a) {
		lock();
		List<AssignmentStrategy.Candidate> candidates = underwriters(List.of(Role.UNDERWRITER)).stream()
				.filter(u -> u.available() && u.covers(a.productCode()))
				.map(u -> new AssignmentStrategy.Candidate(u.staffId(), u.name(), u.pending(), u.lastAssignedAt()))
				.toList();
		Optional<AssignmentStrategy.Candidate> chosen = strategy.choose(candidates);
		chosen.ifPresent(c -> a.assign(c.staffId(), OffsetDateTime.now(clock)));
		return chosen.flatMap(c -> staff.byId(c.staffId()));
	}

	static BusinessException noUnderwriter(String productCode) {
		return new BusinessException("NO_UNDERWRITER", productCode + " 상품을 담당하는 배정 가능한 심사역이 없습니다");
	}

	/** 수동 배정·재배정 (심사팀장·본부장): 심사역·심사팀장 중 담당 상품이 맞고 배정 가능한 사람 */
	@Transactional
	CurrentStaff assignManual(LoanApplication a, long staffId) {
		lock();
		UnderwriterView target = underwriters(List.of(Role.UNDERWRITER, Role.UNDERWRITING_LEAD)).stream()
				.filter(u -> u.staffId() == staffId).findFirst()
				.orElseThrow(() -> new BusinessException("UNDERWRITER_NOT_FOUND", "배정할 수 있는 심사 직원이 아닙니다"));
		if (!target.available()) {
			throw new BusinessException("UNDERWRITER_UNAVAILABLE", target.name() + "님은 배정 불가(부재) 상태입니다");
		}
		if (!target.covers(a.productCode())) {
			throw new BusinessException("UNDERWRITER_PRODUCT", target.name() + "님의 담당 상품이 아닙니다");
		}
		a.assign(staffId, OffsetDateTime.now(clock));
		return staff.byId(staffId).orElseThrow();
	}

	/** 심사 직원 현황 (역할 순, 직원 ID 순) */
	List<UnderwriterView> underwriters(List<Role> roles) {
		Map<Long, int[]> pending = new HashMap<>();
		Map<Long, OffsetDateTime> last = new HashMap<>();
		jdbc.sql("""
				select underwriter_id,
				       count(*) filter (where status in ('ASSIGNED', 'UNDERWRITING')) as pending,
				       max(assigned_at) as last_assigned_at
				from loan_application where underwriter_id is not null group by underwriter_id
				""").query((rs, n) -> {
			long id = rs.getLong(1);
			pending.put(id, new int[] { rs.getInt(2) });
			OffsetDateTime at = rs.getObject(3, OffsetDateTime.class);
			if (at != null) {
				last.put(id, at);
			}
			return id;
		}).list();
		List<UnderwriterView> views = new ArrayList<>();
		for (Role role : roles) {
			for (CurrentStaff s : staff.activeByRole(role)) {
				Optional<UnderwriterProfile> p = profiles.findById(s.id());
				views.add(new UnderwriterView(s.id(), s.loginId(), s.name(), role.name(),
						p.map(UnderwriterProfile::products).orElse(List.of()), p.isEmpty(),
						p.map(UnderwriterProfile::available).orElse(true),
						pending.getOrDefault(s.id(), new int[] { 0 })[0], last.get(s.id())));
			}
		}
		return views;
	}

	@Transactional
	UnderwriterProfile saveProfile(long staffId, List<String> productCodes, boolean available) {
		boolean underwriting = staff.byId(staffId)
				.map(s -> s.role() == Role.UNDERWRITER || s.role() == Role.UNDERWRITING_LEAD).orElse(false);
		if (!underwriting) {
			throw new BusinessException("UNDERWRITER_NOT_FOUND", "심사역·심사팀장만 프로필을 둘 수 있습니다");
		}
		UnderwriterProfile p = profiles.findById(staffId).orElse(null);
		if (p == null) {
			return profiles.save(new UnderwriterProfile(staffId, productCodes, available));
		}
		p.update(productCodes, available);
		return p;
	}

	private void lock() {
		jdbc.sql("select pg_advisory_xact_lock(:k)").param("k", LOCK_KEY).query((rs, n) -> 1).list();
	}
}
