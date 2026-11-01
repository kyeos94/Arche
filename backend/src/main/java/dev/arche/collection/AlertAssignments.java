package dev.arche.collection;

/** 직원 퇴사 (R75): 그 직원이 아직 읽지 않은 추심 알림의 수와 이관 */
public interface AlertAssignments {

	int openCount(long staffId);

	/** 읽지 않은 알림을 다른 직원에게 넘긴다. 넘긴 알림 수 */
	int reassignOpen(long fromStaffId, long toStaffId);
}
