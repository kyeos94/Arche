# Arche 프런트엔드

예시대부(가상) 여신관리 관리자 화면. Vite + React 19(React Compiler) + TanStack Query + shadcn/ui 스타일 컴포넌트 + react-hook-form + zod 4.

```bash
npm ci
npm run dev     # http://localhost:5173, /api·/actuator → http://localhost:8080 프록시
npm test        # Vitest
npm run build   # tsc -b && vite build
```

## 구조

| 경로 | 내용 |
| --- | --- |
| `src/api/client.ts` | fetch 래퍼. 세션 쿠키 + `XSRF-TOKEN` 쿠키 → `X-XSRF-TOKEN` 헤더, 401이면 로그인 화면 |
| `src/api/endpoints.ts` | 타입 있는 API 함수와 TanStack Query 키 (`['loan', id, ...]`로 계약 단위 무효화) |
| `src/lib/format.ts` | 금액(천 단위 + 원)·날짜(yyyy-MM-dd)·상태 라벨 단일 포맷터 |
| `src/lib/schemas.ts` | zod 입력 검증 (약정일 5·10·15·20·25일, 금액, 정정 유형별 필수값) |
| `src/features/loans` | 계약 목록(상태 탭·URL 동기화), 계약 상세(고정 요약 바 + 스케줄·원장·입금내역·이력 탭), 계약 등록 |
| `src/features/payments` | 미확인 입금 매칭, 입금 취소·이동·금액 정정, 마감 후 정정 승인 |
| `src/features/bulk` | 입금 일괄 등록 (업로드 → 검증 → 실행 → 실패 행 다운로드) |
| `src/features/admin` | 일마감, 직원 잠금 해제, 접근 기록 |

버튼은 서버가 준 권한 코드(`permissions[]`)로 숨기지만, 실제 통제는 서버의 메서드 보안이다.

## 화면에서 golden 사례 재현

- **G11** 미확인 입금 → 계약 매칭: 기산일은 원래 입금일, 연체가 소급으로 사라진다
- **G27** 계약 상세 › 입금내역 › 입금 삭제: 마감 후면 시스템관리자 요청 → 채권팀장 승인, 연체가 약정일부터 되살아난다
- **G28** 입금내역 › 계약 이동: 같은 정정번호로 원 계약 취소 + 대상 계약 소급 입금
