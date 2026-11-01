package dev.arche.staff.internal;

import dev.arche.common.jpa.BaseEntity;
import dev.arche.staff.CurrentStaff;
import dev.arche.staff.Role;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 직원 계정. 로그인 정책은 정책서 7장 (5회 실패 잠금, 90일 변경, 초기 비밀번호 변경).
 */
@Entity
@Table(name = "staff")
class Staff extends BaseEntity {

	static final int MAX_FAILED_ATTEMPTS = 5;
	static final int PASSWORD_VALID_DAYS = 90;
	static final int PASSWORD_WARN_DAYS = 80;

	private String loginId;
	private String name;
	@Enumerated(EnumType.STRING)
	private Role role;
	private Long teamId;
	private String passwordHash;
	private LocalDate passwordChangedAt;
	private boolean mustChangePassword;
	private int failedAttempts;
	private boolean locked;
	private boolean enabled;
	private OffsetDateTime resignedAt;

	protected Staff() {
	}

	Staff(String loginId, String name, Role role, Long teamId, String passwordHash, LocalDate today,
			boolean mustChangePassword) {
		this.loginId = loginId;
		this.name = name;
		this.role = role;
		this.teamId = teamId;
		this.passwordHash = passwordHash;
		this.passwordChangedAt = today;
		this.mustChangePassword = mustChangePassword;
		this.enabled = true;
	}

	CurrentStaff toCurrent() {
		return new CurrentStaff(getId(), loginId, name, role, teamId);
	}

	/** 실패 1회 기록. 5회 연속이면 잠금 (관리자 해제) */
	void recordFailure() {
		failedAttempts++;
		if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
			locked = true;
		}
	}

	void recordSuccess() {
		failedAttempts = 0;
	}

	/** 역할·팀 변경 (R71). 검증은 StaffRoleService */
	void changeRole(Role newRole, Long newTeamId) {
		this.role = newRole;
		this.teamId = newTeamId;
	}

	/** 퇴사 (R75): 로그인할 수 없게 하고 퇴사 시각을 남긴다. 검증·담당 건 이관은 호출하는 쪽 */
	void resign(OffsetDateTime at) {
		enabled = false;
		resignedAt = at;
	}

	/** 복직 (R75): 다시 로그인할 수 있게 하되 첫 로그인에 비밀번호를 바꾸게 한다 */
	void reinstate(String newHash, LocalDate today) {
		enabled = true;
		resignedAt = null;
		resetPassword(newHash, today);
	}

	OffsetDateTime resignedAt() {
		return resignedAt;
	}

	void unlock() {
		locked = false;
		failedAttempts = 0;
	}

	void changePassword(String newHash, LocalDate today) {
		passwordHash = newHash;
		passwordChangedAt = today;
		mustChangePassword = false;
	}

	void resetPassword(String newHash, LocalDate today) {
		passwordHash = newHash;
		passwordChangedAt = today;
		mustChangePassword = true;
		unlock();
	}

	boolean passwordChangeRequired(LocalDate today) {
		return mustChangePassword || ChronoUnit.DAYS.between(passwordChangedAt, today) >= PASSWORD_VALID_DAYS;
	}

	/** 80일부터 경고: 만료까지 남은 일수, 경고 대상이 아니면 -1 */
	long daysUntilPasswordExpiry(LocalDate today) {
		long used = ChronoUnit.DAYS.between(passwordChangedAt, today);
		return used >= PASSWORD_WARN_DAYS ? Math.max(0, PASSWORD_VALID_DAYS - used) : -1;
	}

	String loginId() {
		return loginId;
	}

	String passwordHash() {
		return passwordHash;
	}

	boolean locked() {
		return locked;
	}

	boolean enabled() {
		return enabled;
	}

	Long teamId() {
		return teamId;
	}

	Role role() {
		return role;
	}

	String name() {
		return name;
	}

	int failedAttempts() {
		return failedAttempts;
	}
}
