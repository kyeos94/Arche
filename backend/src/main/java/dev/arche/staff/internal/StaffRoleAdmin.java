package dev.arche.staff.internal;

import dev.arche.common.BusinessException;
import dev.arche.staff.CurrentStaff;
import dev.arche.staff.Role;
import dev.arche.staff.StaffDirectory;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 직원 역할·팀 변경 (R71). 권한은 역할에서 따라오므로(R69) 직원 한 명의 권한을 바꾸려면 역할을 바꾼다.
 */
@Service
class StaffRoleService {

	record Option(String code, String label) {
	}

	record TeamOption(long id, String name) {
	}

	record Options(List<Option> roles, List<TeamOption> teams) {
	}

	record Change(long id, long staffId, String loginId, String staffName, String fromRole, String toRole,
			String fromTeam, String toTeam, String changedBy, OffsetDateTime changedAt, String reason) {
	}

	private final StaffRepository staff;
	private final StaffDirectory directory;
	private final DefaultAccessLogger accessLog;
	private final SessionRegistry sessions;
	private final JdbcClient jdbc;
	private final Clock clock;

	StaffRoleService(StaffRepository staff, StaffDirectory directory, DefaultAccessLogger accessLog,
			SessionRegistry sessions, JdbcClient jdbc, Clock clock) {
		this.staff = staff;
		this.directory = directory;
		this.accessLog = accessLog;
		this.sessions = sessions;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/** 바꿀 수 있는 역할(데모 전용 제외)과 팀 */
	@Transactional(readOnly = true)
	Options options() {
		List<Option> roles = assignableRoles().stream().map(r -> new Option(r.name(), r.label())).toList();
		List<TeamOption> teams = jdbc.sql("select id, name from team order by id")
				.query((rs, n) -> new TeamOption(rs.getLong("id"), rs.getString("name"))).list();
		return new Options(roles, teams);
	}

	static List<Role> assignableRoles() {
		return Arrays.stream(Role.values()).filter(r -> r != Role.DEMO_ALL).toList();
	}

	@Transactional
	void change(long staffId, String roleCode, Long teamId, String reason) {
		String why = PermissionAdminService.requireReason(reason);
		CurrentStaff me = directory.current();
		Staff target = staff.findById(staffId)
				.orElseThrow(() -> new BusinessException("STAFF_NOT_FOUND", "직원이 없습니다"));
		Role to = parseRole(roleCode);
		Long team = teamId;
		check(me.id(), target.getId(), target.role(), to, team != null && teamExists(team),
				target.role() == Role.SYSTEM_ADMIN ? otherActiveAdmins(target.getId()) : 1);
		if (to == target.role() && team.equals(target.teamId())) {
			throw new BusinessException("NO_CHANGE", "역할과 팀이 지금과 같습니다");
		}
		Role from = target.role();
		Long fromTeam = target.teamId();
		target.changeRole(to, team);
		jdbc.sql("""
				insert into staff_role_change (staff_id, from_role, to_role, from_team, to_team, changed_by, changed_at, reason)
				values (:s, :fr, :tr, :ft, :tt, :by, :at, :why)
				""").param("s", target.getId()).param("fr", from.name()).param("tr", to.name()).param("ft", fromTeam)
				.param("tt", team).param("by", me.id()).param("at", OffsetDateTime.now(clock)).param("why", why)
				.update();
		accessLog.log("STAFF_ROLE_CHANGED", "STAFF", target.loginId(),
				from.name() + "/" + fromTeam + " → " + to.name() + "/" + team + " · " + why);
		expireSessionsAfterCommit(target.getId());
	}

	/**
	 * 변경 가능 여부 (R71).
	 *
	 * @param otherActiveAdmins 대상을 뺀, 쓸 수 있는 시스템관리자 수 (대상이 시스템관리자일 때만 의미)
	 */
	static void check(long meId, long targetId, Role from, Role to, boolean teamOk, long otherActiveAdmins) {
		if (meId == targetId) {
			throw new BusinessException("SELF_ROLE_CHANGE", "자기 역할·팀은 바꿀 수 없습니다. 다른 시스템관리자에게 요청하세요");
		}
		if (from == Role.DEMO_ALL || to == Role.DEMO_ALL) {
			throw new BusinessException("DEMO_ROLE_LOCKED", "데모 전용 역할은 바꾸거나 줄 수 없습니다");
		}
		if (!teamOk) {
			throw new BusinessException("TEAM_REQUIRED", "팀을 선택하세요");
		}
		if (from == Role.SYSTEM_ADMIN && to != Role.SYSTEM_ADMIN && otherActiveAdmins < 1) {
			throw new BusinessException("LAST_ADMIN", "시스템관리자가 한 명은 남아 있어야 합니다");
		}
	}

	@Transactional(readOnly = true)
	List<Change> changes(Long staffId) {
		return jdbc.sql("""
				select c.id, c.staff_id, s.login_id, s.name as staff_name, c.from_role, c.to_role,
				       ft.name as from_team, tt.name as to_team, b.name as changed_by, c.changed_at, c.reason
				from staff_role_change c
				join staff s on s.id = c.staff_id
				join staff b on b.id = c.changed_by
				left join team ft on ft.id = c.from_team
				left join team tt on tt.id = c.to_team
				where (:staffId = 0 or c.staff_id = :staffId)
				order by c.id desc limit 200
				""").param("staffId", staffId == null ? 0L : staffId).query((rs, n) -> new Change(rs.getLong("id"),
				rs.getLong("staff_id"), rs.getString("login_id"), rs.getString("staff_name"),
				label(rs.getString("from_role")), label(rs.getString("to_role")), rs.getString("from_team"),
				rs.getString("to_team"), rs.getString("changed_by"), rs.getObject("changed_at", OffsetDateTime.class),
				rs.getString("reason"))).list();
	}

	private boolean teamExists(long teamId) {
		return jdbc.sql("select count(*) from team where id = :id").param("id", teamId).query(Long.class).single() > 0;
	}

	private long otherActiveAdmins(long exceptId) {
		return staff.findAll().stream().filter(s -> s.role() == Role.SYSTEM_ADMIN && s.enabled() && !s.locked()
				&& !s.getId().equals(exceptId)).count();
	}

	/** 바뀐 역할은 세션에 남은 예전 역할로 쓰이면 안 되므로, 커밋 뒤 그 직원의 세션을 끊어 다시 로그인하게 한다 */
	private void expireSessionsAfterCommit(long staffId) {
		Runnable expire = () -> {
			for (Object principal : sessions.getAllPrincipals()) {
				if (principal instanceof StaffPrincipal p && p.staff().id() == staffId) {
					sessions.getAllSessions(principal, false).forEach(SessionInformation::expireNow);
				}
			}
		};
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					expire.run();
				}
			});
		} else {
			expire.run();
		}
	}

	private static Role parseRole(String code) {
		try {
			return Role.valueOf(code);
		} catch (IllegalArgumentException | NullPointerException e) {
			throw new BusinessException("UNKNOWN_ROLE", "없는 역할입니다");
		}
	}

	private static String label(String role) {
		try {
			return Role.valueOf(role).label();
		} catch (IllegalArgumentException e) {
			return role;
		}
	}
}

@RestController
@PreAuthorize("hasAuthority('admin.manage')")
class StaffRoleController {

	record RoleChange(String role, Long teamId, String reason) {
	}

	private final StaffRoleService roles;

	StaffRoleController(StaffRoleService roles) {
		this.roles = roles;
	}

	@GetMapping("/api/admin/staff/role-options")
	StaffRoleService.Options options() {
		return roles.options();
	}

	@PostMapping("/api/admin/staff/{id}/role")
	List<StaffRoleService.Change> change(@PathVariable long id, @RequestBody RoleChange body) {
		roles.change(id, body.role(), body.teamId(), body.reason());
		return roles.changes(id);
	}

	@GetMapping("/api/admin/staff/role-changes")
	List<StaffRoleService.Change> changes(@org.springframework.web.bind.annotation.RequestParam(required = false) Long staffId) {
		return roles.changes(staffId);
	}
}
