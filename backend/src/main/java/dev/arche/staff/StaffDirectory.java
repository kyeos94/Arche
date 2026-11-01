package dev.arche.staff;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 다른 모듈이 직원 정보를 얻는 공개 API.
 */
public interface StaffDirectory {

	/** 현재 요청의 로그인 직원. 배치·웹훅처럼 사용자가 없으면 예외 */
	CurrentStaff current();

	Optional<CurrentStaff> currentIfAny();

	/** 계약 조회 데이터 범위 (담당 건 / 팀 전체 / 전체) */
	DataScope loanScope();

	Optional<CurrentStaff> byId(long staffId);

	Optional<CurrentStaff> byLoginId(String loginId);

	/** 로그인할 수 있는(사용 중·잠기지 않은) 해당 역할 직원. 심사자 배정 후보 등 (R55) */
	List<CurrentStaff> activeByRole(Role role);

	/** 로그인할 수 있는(사용 중·잠기지 않은) 모든 직원. 쪽지 받는 사람 고르기 등 */
	List<CurrentStaff> active();

	/** 팀 id → 팀 이름 */
	Map<Long, String> teamNames();
}
