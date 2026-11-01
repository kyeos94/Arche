package dev.arche.staff;

/** 직원 역할 (정책서 7장) */
public enum Role {
	UNDERWRITER("심사역"),
	UNDERWRITING_LEAD("심사팀장"),
	COLLECTOR("채권담당"),
	COLLECTION_LEAD("채권팀장"),
	TELLER("출납"),
	HEAD("본부장"),
	SYSTEM_ADMIN("시스템관리자"),
	/**
	 * 데모 전용: 모든 메뉴·권한, 전체 데이터 범위. 정책서 9.1 역할이 아니며 데모 모드에서만 만들어지고 로그인할 수 있다.
	 * 작성자 ≠ 승인자 검사는 그대로 적용된다
	 */
	DEMO_ALL("데모(전체 메뉴)");

	private final String label;

	Role(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}
}
