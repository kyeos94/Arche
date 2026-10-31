package dev.arche.staff;

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
}
