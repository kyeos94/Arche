package dev.arche.staff.internal;

import dev.arche.common.BusinessException;
import dev.arche.staff.CurrentStaff;
import dev.arche.staff.Permission;
import dev.arche.staff.Role;
import dev.arche.staff.StaffDirectory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 권한 관리 (R69). 정책서 9.1 기본값은 {@link Permission} enum에 있고, 시스템관리자가 바꾼 칸만
 * role_permission_override에 저장한다. 바꾼 값은 즉시 {@link Permission#applyOverrides}로 반영하고,
 * 다른 서버의 변경은 30초마다 이력 번호를 보고 다시 읽는다.
 */
@Service
class PermissionAdminService implements SmartInitializingSingleton {

	private static final Logger log = LoggerFactory.getLogger(PermissionAdminService.class);

	/** 화면에 보일 권한 이름 (정책서 9.1 행) */
	static final Map<Permission, String> LABELS = labels();

	/** 바꿀 수 없는 칸: 시스템관리자의 관리 권한을 빼면 권한을 되돌릴 사람이 없어진다 */
	static boolean locked(Role role, Permission permission) {
		return role == Role.DEMO_ALL || (role == Role.SYSTEM_ADMIN && permission == Permission.ADMIN);
	}

	record Cell(String role, boolean granted, boolean byDefault, boolean locked) {
	}

	record Row(String code, String label, List<Cell> cells) {
	}

	record RoleView(String code, String label) {
	}

	record Matrix(List<RoleView> roles, List<Row> permissions, int changedCells) {
	}

	record Change(long id, String role, String roleLabel, String permission, String permissionLabel, String action,
			String changedBy, OffsetDateTime changedAt, String reason) {
	}

	private final JdbcClient jdbc;
	private final StaffDirectory staff;
	private final DefaultAccessLogger accessLog;
	private final Clock clock;
	private volatile long loadedVersion = -1;

	PermissionAdminService(JdbcClient jdbc, StaffDirectory staff, DefaultAccessLogger accessLog, Clock clock) {
		this.jdbc = jdbc;
		this.staff = staff;
		this.accessLog = accessLog;
		this.clock = clock;
	}

	/** 웹 서버가 요청을 받기 전에 바뀐 권한을 읽어 둔다 (Flyway는 이미 끝난 시점) */
	@Override
	public void afterSingletonsInstantiated() {
		reload();
	}

	/** 다른 서버에서 바꾼 권한 반영 */
	@Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
	void refreshIfChanged() {
		if (version() != loadedVersion) {
			reload();
		}
	}

	synchronized void reload() {
		long version = version();
		Map<Permission, Map<Role, Boolean>> overrides = new EnumMap<>(Permission.class);
		jdbc.sql("select role, permission, granted from role_permission_override").query((rs, n) -> {
			try {
				Permission p = Permission.byCode(rs.getString("permission"));
				Role r = Role.valueOf(rs.getString("role"));
				overrides.computeIfAbsent(p, k -> new EnumMap<>(Role.class)).put(r, rs.getBoolean("granted"));
			} catch (IllegalArgumentException e) {
				// 코드에서 없어진 권한·역할은 무시
				log.warn("알 수 없는 권한 변경 행 무시: {} / {}", rs.getString("role"), rs.getString("permission"));
			}
			return null;
		}).list();
		Permission.applyOverrides(overrides);
		loadedVersion = version;
	}

	private long version() {
		return jdbc.sql("select coalesce(max(id), 0) from permission_change_log").query(Long.class).single();
	}

	@Transactional(readOnly = true)
	Matrix matrix() {
		List<Role> roles = editableRoles();
		int changed = 0;
		List<Row> rows = new java.util.ArrayList<>();
		for (Permission p : Permission.values()) {
			List<Cell> cells = new java.util.ArrayList<>();
			for (Role r : roles) {
				boolean granted = p.grantedTo(r);
				boolean def = p.grantedByDefault(r);
				if (granted != def) {
					changed++;
				}
				cells.add(new Cell(r.name(), granted, def, locked(r, p)));
			}
			rows.add(new Row(p.code(), LABELS.getOrDefault(p, p.code()), cells));
		}
		return new Matrix(roles.stream().map(r -> new RoleView(r.name(), r.label())).toList(), rows, changed);
	}

	/** 한 칸 변경. 기본값과 같아지면 저장된 변경을 지운다 */
	@Transactional
	void set(String roleCode, String permissionCode, boolean granted, String reason) {
		Role role = parseRole(roleCode);
		Permission permission;
		try {
			permission = Permission.byCode(permissionCode);
		} catch (IllegalArgumentException e) {
			throw new BusinessException("UNKNOWN_PERMISSION", "없는 권한입니다");
		}
		String why = requireReason(reason);
		if (locked(role, permission)) {
			throw new BusinessException("PERMISSION_LOCKED", "이 권한은 바꿀 수 없습니다");
		}
		CurrentStaff me = staff.current();
		OffsetDateTime now = OffsetDateTime.now(clock);
		String action;
		if (granted == permission.grantedByDefault(role)) {
			jdbc.sql("delete from role_permission_override where role = :r and permission = :p")
					.param("r", role.name()).param("p", permission.code()).update();
			action = "DEFAULT";
		} else {
			jdbc.sql("""
					insert into role_permission_override (role, permission, granted, changed_by, changed_at, reason)
					values (:r, :p, :g, :by, :at, :why)
					on conflict (role, permission) do update
					set granted = excluded.granted, changed_by = excluded.changed_by,
					    changed_at = excluded.changed_at, reason = excluded.reason
					""").param("r", role.name()).param("p", permission.code()).param("g", granted)
					.param("by", me.id()).param("at", now).param("why", why).update();
			action = granted ? "GRANT" : "REVOKE";
		}
		writeLog(role.name(), permission.code(), action, me.id(), now, why);
		accessLog.log("PERMISSION_CHANGED", "PERMISSION", role.name() + ":" + permission.code(), action + " " + why);
		reload();
	}

	/** 정책서 9.1 기본값으로 모두 되돌림 */
	@Transactional
	void resetAll(String reason) {
		String why = requireReason(reason);
		CurrentStaff me = staff.current();
		OffsetDateTime now = OffsetDateTime.now(clock);
		jdbc.sql("delete from role_permission_override").update();
		writeLog(null, null, "RESET_ALL", me.id(), now, why);
		accessLog.log("PERMISSION_RESET", "PERMISSION", null, why);
		reload();
	}

	@Transactional(readOnly = true)
	List<Change> changes(int limit) {
		return jdbc.sql("""
				select l.id, l.role, l.permission, l.action, s.name as changed_by, l.changed_at, l.reason
				from permission_change_log l join staff s on s.id = l.changed_by
				order by l.id desc limit :limit
				""").param("limit", Math.min(Math.max(limit, 1), 500)).query((rs, n) -> {
			String role = rs.getString("role");
			String perm = rs.getString("permission");
			return new Change(rs.getLong("id"), role, role == null ? null : roleLabel(role), perm,
					perm == null ? null : permissionLabel(perm), rs.getString("action"), rs.getString("changed_by"),
					rs.getObject("changed_at", OffsetDateTime.class), rs.getString("reason"));
		}).list();
	}

	private void writeLog(String role, String permission, String action, long by, OffsetDateTime at, String why) {
		jdbc.sql("""
				insert into permission_change_log (role, permission, action, changed_by, changed_at, reason)
				values (:r, :p, :a, :by, :at, :why)
				""").param("r", role).param("p", permission).param("a", action).param("by", by).param("at", at)
				.param("why", why).update();
	}

	static List<Role> editableRoles() {
		return Arrays.stream(Role.values()).filter(r -> r != Role.DEMO_ALL).toList();
	}

	private static Role parseRole(String code) {
		try {
			Role role = Role.valueOf(code);
			if (role == Role.DEMO_ALL) {
				throw new BusinessException("PERMISSION_LOCKED", "데모 전용 역할은 바꿀 수 없습니다");
			}
			return role;
		} catch (IllegalArgumentException e) {
			throw new BusinessException("UNKNOWN_ROLE", "없는 역할입니다");
		}
	}

	static String requireReason(String reason) {
		String why = reason == null ? "" : reason.trim();
		if (why.length() < 2 || why.length() > 200) {
			throw new BusinessException("REASON_REQUIRED", "변경 사유를 2~200자로 입력하세요");
		}
		return why;
	}

	private static String roleLabel(String code) {
		try {
			return Role.valueOf(code).label();
		} catch (IllegalArgumentException e) {
			return code;
		}
	}

	private static String permissionLabel(String code) {
		try {
			return LABELS.getOrDefault(Permission.byCode(code), code);
		} catch (IllegalArgumentException e) {
			return code;
		}
	}

	private static Map<Permission, String> labels() {
		Map<Permission, String> m = new EnumMap<>(Permission.class);
		m.put(Permission.APPLICATION_REVIEW, "신청 조회·심사 입력");
		m.put(Permission.APPLICATION_APPROVE_SMALL, "심사 승인 (1,000만원 이하)");
		m.put(Permission.APPLICATION_APPROVE_LARGE, "심사 승인 (1,000만원 초과)");
		m.put(Permission.LOAN_READ, "계약 조회");
		m.put(Permission.CUSTOMER_ID_REVEAL, "고객 식별번호 원문 조회");
		m.put(Permission.PAYMENT_REGISTER, "입금 등록·미확인 입금 매칭");
		m.put(Permission.WITHDRAWAL_CREATE, "출금 작성");
		m.put(Permission.WITHDRAWAL_APPROVE_SMALL, "출금 승인 (1,000만원 이하)");
		m.put(Permission.WITHDRAWAL_APPROVE_LARGE, "출금 승인 (1,000만원 초과)");
		m.put(Permission.TERM_CHANGE, "금리·약정일 변경");
		m.put(Permission.WORKOUT_LATE_INTEREST, "채무조정 연체이자 감면");
		m.put(Permission.WORKOUT_NORMAL_INTEREST, "채무조정 정상이자 감면");
		m.put(Permission.WORKOUT_PRINCIPAL, "채무조정 원금 감면");
		m.put(Permission.CHARGE_OFF_SALE_CONFIRM, "상각·매각 확정");
		m.put(Permission.EXCEL_DOWNLOAD, "엑셀 다운로드");
		m.put(Permission.ADMIN, "직원·권한·코드·배치 관리");
		m.put(Permission.CLOSED_TX_CANCEL, "마감 후 거래 취소");
		m.put(Permission.CLOSED_TX_CANCEL_APPROVE, "마감 후 거래 취소 승인");
		m.put(Permission.LOAN_CREATE, "계약 직접 생성·양수 등록");
		m.put(Permission.COLLECTION_ACTIVITY, "추심 연락 기록·분할납부 약속 등록");
		m.put(Permission.LEGAL_EVENT, "소멸시효 중단 사유·법조치 기록");
		m.put(Permission.REPORT_READ, "보고서 조회");
		m.put(Permission.LOAN_ADJUST_REQUEST, "수기 조정·상계·상태 되돌리기 요청");
		m.put(Permission.LOAN_ADJUST_APPROVE, "수기 조정·상계·상태 되돌리기 승인");
		m.put(Permission.COLLECTION_STOP, "추심 중지 등록·해제");
		m.put(Permission.APPLICATION_ASSIGN, "심사자 배정·심사자 프로필 관리");
		m.put(Permission.BROKER_MANAGE, "중개업자 관리");
		return Map.copyOf(m);
	}
}

/**
 * 로그인 중인 세션의 권한을 지금 권한표에 맞춘다. 권한을 빼면 다음 요청부터 바로 막히게 하려는 것 (R69).
 * 세션에 저장된 권한 목록이 지금 값과 다를 때만 새로 만들어 저장한다.
 */
class AuthorityRefreshFilter extends OncePerRequestFilter {

	private final SecurityContextRepository repository;

	AuthorityRefreshFilter(SecurityContextRepository repository) {
		this.repository = repository;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null && auth.getPrincipal() instanceof StaffPrincipal p) {
			Authentication fresh = p.toAuthentication();
			if (!codes(fresh).equals(codes(auth))) {
				SecurityContext context = SecurityContextHolder.createEmptyContext();
				context.setAuthentication(fresh);
				SecurityContextHolder.setContext(context);
				repository.saveContext(context, request, response);
			}
		}
		chain.doFilter(request, response);
	}

	private static Set<String> codes(Authentication a) {
		return a.getAuthorities().stream().map(GrantedAuthority::getAuthority)
				.collect(Collectors.toCollection(HashSet::new));
	}
}
