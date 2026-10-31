package dev.arche.staff;

/** 직원 역할 (정책서 7장) */
public enum Role {
	UNDERWRITER("심사역"),
	UNDERWRITING_LEAD("심사팀장"),
	COLLECTOR("채권담당"),
	COLLECTION_LEAD("채권팀장"),
	TELLER("출납"),
	HEAD("본부장"),
	SYSTEM_ADMIN("시스템관리자");

	private final String label;

	Role(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}
}
