# ADR 0004. 인증·권한

- 상태: 채택 (2026-10-31)

## 결정
- 서블릿 세션 + JSON 로그인(`POST /api/auth/login`). SPA는 `XSRF-TOKEN` 쿠키를 `X-XSRF-TOKEN` 헤더로 돌려보낸다 (`csrf.spa()`).
- 동시 로그인 1개: 새 로그인이 이전 세션을 만료시킨다 (`ConcurrentSessionControlAuthenticationStrategy`, 최대 1).
- 세션 30분 (`server.servlet.session.timeout`). 5회 연속 실패 잠금(관리자 해제), 90일 비밀번호 변경(80일부터 경고), 초기 비밀번호 변경 강제.
  변경이 필요한 세션은 `/api/auth/**` 외 API를 쓸 수 없다.
- 권한은 정책서 9.1 표를 `Permission` enum 행렬로 옮기고(`PermissionMatrixTest`가 표와 대조), 문자열 코드를
  `@PreAuthorize("hasAuthority('loan.read')")`로 검사한다. 역할마다 1개 역할만 둔다(직원별 예외 권한은 확장 지점).
- 데이터 범위: 채권담당 = 담당 건, 채권팀장 = 같은 팀 직원의 담당 건, 출납·본부장·시스템관리자 = 전체.
- 승인 업무는 작성자 ≠ 승인자를 서비스에서 검사한다(마감 후 입금 취소 R28).
- 접근 기록: 로그인 성공·실패·잠금, 식별번호 원문 조회, 직원 관리 작업을 `access_log`에 별도 트랜잭션으로 남긴다.

## 정책서에 없어 정한 것
- 계약 직접 생성·양수 등록(`loan.create`): 본부장만 허용. 사용자 확인 후 정책서 R34와 9.1 표에 추가했다 (2026-10-31).
- 비용 적재(`/api/loans/{id}/costs`): 입금 등록 권한(`payment.register`)과 같게 두었다.
- 추심 연락 기록·분할납부 약속 등록(`collection.activity`): 채권담당·채권팀장·본부장. 소멸시효 중단 사유 기록(`collection.legal`): 채권팀장·본부장. 사용자 확인 후 정책서 R37과 9.1 표에 추가했다 (2026-10-31).
