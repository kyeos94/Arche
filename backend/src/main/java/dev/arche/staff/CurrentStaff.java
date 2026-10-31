package dev.arche.staff;

/** 로그인한 직원 */
public record CurrentStaff(long id, String loginId, String name, Role role, Long teamId) {

	public boolean has(Permission permission) {
		return permission.grantedTo(role);
	}
}
