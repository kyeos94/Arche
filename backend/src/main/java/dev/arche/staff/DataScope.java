package dev.arche.staff;

import java.util.Set;

/**
 * 계약 조회 데이터 범위 (정책서 9.1 "담당 건 / 팀 전체"). staffIds가 null이면 전체.
 */
public record DataScope(Kind kind, Set<Long> staffIds) {

	public enum Kind {
		OWN, TEAM, ALL
	}

	public static DataScope all() {
		return new DataScope(Kind.ALL, null);
	}

	public boolean allows(Long assignedStaffId) {
		return kind == Kind.ALL || (assignedStaffId != null && staffIds.contains(assignedStaffId));
	}
}
