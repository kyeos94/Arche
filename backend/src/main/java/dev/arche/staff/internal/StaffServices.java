package dev.arche.staff.internal;

import dev.arche.common.BusinessException;
import dev.arche.staff.AccessLogger;
import dev.arche.staff.CurrentStaff;
import dev.arche.staff.DataScope;
import dev.arche.staff.Permission;
import dev.arche.staff.Role;
import dev.arche.staff.StaffDirectory;
import jakarta.servlet.http.HttpServletRequest;
import java.io.Serializable;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

interface StaffRepository extends JpaRepository<Staff, Long> {

	Optional<Staff> findByLoginId(String loginId);

	List<Staff> findByTeamId(Long teamId);

	List<Staff> findByRoleAndEnabledTrueAndLockedFalseOrderById(Role role);
}

interface AccessLogRepository extends JpaRepository<AccessLogEntry, Long> {

	Page<AccessLogEntry> findAllByOrderByIdDesc(Pageable pageable);
}

/** 세션에 저장되는 인증 주체 */
record StaffPrincipal(CurrentStaff staff, boolean passwordChangeRequired) implements Serializable {

	static final String PASSWORD_CHANGE_REQUIRED = "PASSWORD_CHANGE_REQUIRED";

	Authentication toAuthentication() {
		List<GrantedAuthority> authorities = new ArrayList<>();
		if (passwordChangeRequired) {
			authorities.add(new SimpleGrantedAuthority(PASSWORD_CHANGE_REQUIRED));
		} else {
			authorities.add(new SimpleGrantedAuthority("ROLE_" + staff.role().name()));
			for (Permission p : Permission.of(staff.role())) {
				authorities.add(new SimpleGrantedAuthority(p.code()));
			}
		}
		return UsernamePasswordAuthenticationToken.authenticated(this, null, authorities);
	}

	@Override
	public String toString() {
		return staff.loginId();
	}
}

@Service
class DefaultStaffDirectory implements StaffDirectory {

	private final StaffRepository staff;

	DefaultStaffDirectory(StaffRepository staff) {
		this.staff = staff;
	}

	@Override
	public CurrentStaff current() {
		return currentIfAny().orElseThrow(() -> new BusinessException("UNAUTHENTICATED", "로그인이 필요합니다"));
	}

	@Override
	public Optional<CurrentStaff> currentIfAny() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null && auth.getPrincipal() instanceof StaffPrincipal p) {
			return Optional.of(p.staff());
		}
		return Optional.empty();
	}

	@Override
	@Transactional(readOnly = true)
	public DataScope loanScope() {
		CurrentStaff me = current();
		return switch (me.role()) {
			case COLLECTOR -> new DataScope(DataScope.Kind.OWN, Set.of(me.id()));
			case COLLECTION_LEAD -> new DataScope(DataScope.Kind.TEAM,
					staff.findByTeamId(me.teamId()).stream().map(Staff::getId).collect(Collectors.toSet()));
			case TELLER, HEAD, SYSTEM_ADMIN, DEMO_ALL -> DataScope.all();
			case UNDERWRITER, UNDERWRITING_LEAD -> new DataScope(DataScope.Kind.OWN, Set.of());
		};
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<CurrentStaff> byId(long staffId) {
		return staff.findById(staffId).map(Staff::toCurrent);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<CurrentStaff> byLoginId(String loginId) {
		return staff.findByLoginId(loginId).map(Staff::toCurrent);
	}

	@Override
	@Transactional(readOnly = true)
	public List<CurrentStaff> activeByRole(Role role) {
		return staff.findByRoleAndEnabledTrueAndLockedFalseOrderById(role).stream().map(Staff::toCurrent).toList();
	}
}

@Service
class DefaultAccessLogger implements AccessLogger {

	private final AccessLogRepository logs;
	private final StaffDirectory directory;
	private final Clock clock;

	DefaultAccessLogger(AccessLogRepository logs, StaffDirectory directory, Clock clock) {
		this.logs = logs;
		this.directory = directory;
		this.clock = clock;
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void log(String action, String targetType, String targetId, String reason) {
		Optional<CurrentStaff> me = directory.currentIfAny();
		write(me.map(CurrentStaff::id).orElse(null), me.map(CurrentStaff::loginId).orElse("anonymous"), action,
				targetType, targetId, reason);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void write(Long staffId, String loginId, String action, String targetType, String targetId,
			String reason) {
		logs.save(new AccessLogEntry(staffId, loginId, OffsetDateTime.now(clock), clientIp(), action, targetType,
				targetId, reason));
	}

	private static String clientIp() {
		if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
			HttpServletRequest request = attrs.getRequest();
			return request.getRemoteAddr();
		}
		return null;
	}
}
