package dev.arche.origination;

/**
 * 직원 퇴사 (R75): 심사자로 배정된 진행 중 신청(배정·심사 중·승인·약정)의 수와 이관.
 */
public interface UnderwriterAssignments {

	int openCount(long staffId);

	/**
	 * 진행 중 신청을 다른 심사 직원에게 넘긴다. 받는 직원이 심사 직원이 아니거나 부재 중이거나 담당 상품이 아니면 거절
	 * (수동 재배정과 같은 규칙, R55). 신청마다 상태 이력에 남긴다. 넘긴 신청 수
	 */
	int reassignOpen(long fromStaffId, long toStaffId, String reason);
}
