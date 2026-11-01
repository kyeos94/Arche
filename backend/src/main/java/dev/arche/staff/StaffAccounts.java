package dev.arche.staff;

/**
 * 직원 퇴사 (R75). 담당 건 이관은 여러 모듈에 걸치므로 operation 모듈이 한 트랜잭션으로 묶어 부른다:
 * {@link #checkResignable} → 각 모듈의 담당 건 이관 → {@link #resign}.
 */
public interface StaffAccounts {

	/** 퇴사 처리할 수 있는지 검사하고 대상 직원을 돌려준다 (자기 자신·데모 계정·이미 퇴사·마지막 시스템관리자 불가) */
	CurrentStaff checkResignable(long staffId);

	/** 이관할 담당 건을 받을 수 있는 재직 직원인지 */
	CurrentStaff requireActive(long staffId);

	/**
	 * 퇴사 처리: 로그인 차단, 이력 기록, 커밋 뒤 세션 끊기.
	 *
	 * @param handover 이관 요약 (이력에 남김, 없으면 null)
	 */
	void resign(long staffId, String reason, String handover);
}
