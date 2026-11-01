package dev.arche.staff.internal;

import dev.arche.common.BusinessException;
import dev.arche.staff.CurrentStaff;
import dev.arche.staff.Role;
import dev.arche.staff.StaffAccounts;
import dev.arche.staff.StaffDirectory;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 직원 등록·퇴사·복직 (R75, 가정·검수 필요).
 * <ul>
 * <li>등록: 로그인 ID(영문 소문자로 시작, 영문 소문자·숫자·.-_ 3~30자, 중복 불가), 이름 1~50자, 역할(데모 전용 제외), 팀 필수.
 * 임시 비밀번호는 시스템이 만들어 한 번만 보여 주고 첫 로그인에 바꾸게 한다</li>
 * <li>퇴사: 행을 지우지 않고 로그인만 막는다. 자기 자신·데모 계정·마지막 시스템관리자는 불가. 담당 건 이관은 operation 모듈</li>
 * <li>복직: 다시 로그인할 수 있게 하고 새 임시 비밀번호를 발급한다(퇴사 전 비밀번호는 쓰지 않음)</li>
 * </ul>
 */
@Service
class StaffAccountService implements StaffAccounts {

	static final Pattern LOGIN_ID = Pattern.compile("[a-z][a-z0-9._-]{2,29}");
	private static final String LETTERS = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ";
	private static final String DIGITS = "23456789";
	private static final String SYMBOLS = "!@#$%*";
	private static final SecureRandom RANDOM = new SecureRandom();

	record Created(long id, String loginId, String temporaryPassword) {
	}

	record StatusChange(long id, long staffId, String loginId, String staffName, String action,
			String reason, @Nullable String detail, String changedBy, OffsetDateTime changedAt) {
	}

	private final StaffRepository staff;
	private final StaffDirectory directory;
	private final DefaultAccessLogger accessLog;
	private final PasswordEncoder encoder;
	private final SessionRegistry sessions;
	private final JdbcClient jdbc;
	private final Clock clock;

	StaffAccountService(StaffRepository staff, StaffDirectory directory, DefaultAccessLogger accessLog,
			PasswordEncoder encoder, SessionRegistry sessions, JdbcClient jdbc, Clock clock) {
		this.staff = staff;
		this.directory = directory;
		this.accessLog = accessLog;
		this.encoder = encoder;
		this.sessions = sessions;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Transactional
	Created create(@Nullable String loginId, @Nullable String name, @Nullable String roleCode, @Nullable Long teamId) {
		CurrentStaff me = directory.current();
		String id = loginId == null ? "" : loginId.strip().toLowerCase(Locale.ROOT);
		if (!LOGIN_ID.matcher(id).matches()) {
			throw new BusinessException("LOGIN_ID_INVALID",
					"로그인 ID는 영문 소문자로 시작하고 영문 소문자·숫자·. - _ 로 3~30자입니다");
		}
		if (staff.findByLoginId(id).isPresent()) {
			throw new BusinessException("LOGIN_ID_TAKEN", "이미 쓰고 있는 로그인 ID입니다 (퇴사자 포함)");
		}
		String who = name == null ? "" : name.strip();
		if (who.isEmpty() || who.length() > 50) {
			throw new BusinessException("NAME_INVALID", "이름을 1~50자로 입력하세요");
		}
		Role role = parseRole(roleCode);
		if (teamId == null || !teamExists(teamId)) {
			throw new BusinessException("TEAM_REQUIRED", "팀을 선택하세요");
		}
		String temp = temporaryPassword();
		Staff s = staff.save(new Staff(id, who, role, teamId, encoder.encode(temp), LocalDate.now(clock), true));
		history(s.getId(), "CREATE", "신규 등록 (" + role.label() + ")", null, me.id());
		accessLog.log("STAFF_CREATED", "STAFF", id, role.name() + "/" + teamId);
		return new Created(s.getId(), id, temp);
	}

	@Override
	@Transactional(readOnly = true)
	public CurrentStaff checkResignable(long staffId) {
		CurrentStaff me = directory.current();
		Staff target = find(staffId);
		check(me.id(), target.getId(), target.role(), target.enabled(),
				target.role() == Role.SYSTEM_ADMIN ? otherActiveAdmins(target.getId()) : 1);
		return target.toCurrent();
	}

	/** 퇴사 가능 여부 (테스트에서 직접 부른다) */
	static void check(long meId, long targetId, Role role, boolean enabled, long otherActiveAdmins) {
		if (meId == targetId) {
			throw new BusinessException("SELF_RESIGN", "자기 자신은 퇴사 처리할 수 없습니다. 다른 시스템관리자에게 요청하세요");
		}
		if (role == Role.DEMO_ALL) {
			throw new BusinessException("DEMO_ROLE_LOCKED", "데모 계정은 퇴사 처리할 수 없습니다");
		}
		if (!enabled) {
			throw new BusinessException("ALREADY_RESIGNED", "이미 퇴사한 직원입니다");
		}
		if (role == Role.SYSTEM_ADMIN && otherActiveAdmins < 1) {
			throw new BusinessException("LAST_ADMIN", "시스템관리자가 한 명은 남아 있어야 합니다");
		}
	}

	@Override
	@Transactional(readOnly = true)
	public CurrentStaff requireActive(long staffId) {
		Staff s = find(staffId);
		if (!s.enabled() || s.locked()) {
			throw new BusinessException("SUCCESSOR_INACTIVE", s.name() + "님은 담당 건을 받을 수 없습니다 (퇴사·잠김)");
		}
		return s.toCurrent();
	}

	@Override
	@Transactional
	public void resign(long staffId, String reason, @Nullable String handover) {
		String why = PermissionAdminService.requireReason(reason);
		CurrentStaff me = directory.current();
		Staff target = find(staffId);
		check(me.id(), target.getId(), target.role(), target.enabled(),
				target.role() == Role.SYSTEM_ADMIN ? otherActiveAdmins(target.getId()) : 1);
		target.resign(OffsetDateTime.now(clock));
		history(target.getId(), "RESIGN", why, handover, me.id());
		accessLog.log("STAFF_RESIGNED", "STAFF", target.loginId(), why);
		expireSessionsAfterCommit(target.getId());
	}

	@Transactional
	Created reinstate(long staffId, String reason) {
		String why = PermissionAdminService.requireReason(reason);
		CurrentStaff me = directory.current();
		Staff target = find(staffId);
		if (target.enabled()) {
			throw new BusinessException("NOT_RESIGNED", "퇴사한 직원이 아닙니다");
		}
		String temp = temporaryPassword();
		target.reinstate(encoder.encode(temp), LocalDate.now(clock));
		history(target.getId(), "REINSTATE", why, null, me.id());
		accessLog.log("STAFF_REINSTATED", "STAFF", target.loginId(), why);
		return new Created(target.getId(), target.loginId(), temp);
	}

	@Transactional(readOnly = true)
	List<StatusChange> changes() {
		return jdbc.sql("""
				select c.id, c.staff_id, s.login_id, s.name as staff_name, c.action, c.reason, c.detail,
				       b.name as changed_by, c.changed_at
				from staff_status_change c join staff s on s.id = c.staff_id join staff b on b.id = c.changed_by
				order by c.id desc limit 200
				""").query((rs, n) -> new StatusChange(rs.getLong("id"), rs.getLong("staff_id"),
				rs.getString("login_id"), rs.getString("staff_name"), rs.getString("action"), rs.getString("reason"),
				rs.getString("detail"), rs.getString("changed_by"), rs.getObject("changed_at", OffsetDateTime.class)))
				.list();
	}

	/** 12자: 영문 대·소문자, 숫자, 기호를 하나 이상 섞고 헷갈리는 글자(0 O 1 l I)는 뺀다 */
	static String temporaryPassword() {
		String all = LETTERS + DIGITS + SYMBOLS;
		char[] out = new char[12];
		out[0] = LETTERS.charAt(RANDOM.nextInt(23)); // 소문자 23자
		out[1] = LETTERS.charAt(23 + RANDOM.nextInt(LETTERS.length() - 23)); // 대문자 24자
		out[2] = DIGITS.charAt(RANDOM.nextInt(DIGITS.length()));
		out[3] = SYMBOLS.charAt(RANDOM.nextInt(SYMBOLS.length()));
		for (int i = 4; i < out.length; i++) {
			out[i] = all.charAt(RANDOM.nextInt(all.length()));
		}
		for (int i = out.length - 1; i > 0; i--) {
			int j = RANDOM.nextInt(i + 1);
			char t = out[i];
			out[i] = out[j];
			out[j] = t;
		}
		return new String(out);
	}

	private void history(long staffId, String action, String reason, @Nullable String detail, long by) {
		jdbc.sql("""
				insert into staff_status_change (staff_id, action, reason, detail, changed_by, changed_at)
				values (:s, :a, :r, :d, :by, :at)
				""").param("s", staffId).param("a", action).param("r", reason)
				.param("d", detail == null ? null : detail.length() > 300 ? detail.substring(0, 300) : detail)
				.param("by", by).param("at", OffsetDateTime.now(clock)).update();
	}

	private Staff find(long id) {
		return staff.findById(id).orElseThrow(() -> new BusinessException("STAFF_NOT_FOUND", "직원이 없습니다"));
	}

	private boolean teamExists(long teamId) {
		return jdbc.sql("select count(*) from team where id = :id").param("id", teamId).query(Long.class).single() > 0;
	}

	private long otherActiveAdmins(long exceptId) {
		return staff.findAll().stream().filter(s -> s.role() == Role.SYSTEM_ADMIN && s.enabled() && !s.locked()
				&& !s.getId().equals(exceptId)).count();
	}

	private static Role parseRole(@Nullable String code) {
		try {
			Role r = Role.valueOf(String.valueOf(code));
			if (r == Role.DEMO_ALL) {
				throw new BusinessException("DEMO_ROLE_LOCKED", "데모 전용 역할은 줄 수 없습니다");
			}
			return r;
		} catch (IllegalArgumentException e) {
			throw new BusinessException("UNKNOWN_ROLE", "없는 역할입니다");
		}
	}

	/** 퇴사한 직원의 세션은 커밋 뒤 바로 끊는다 */
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
}
