# ADR 0007. 심사 상세 — 접수 채널·자동 거절·서류·배정·중개수수료·신용조회·본인인증

- 상태: 채택 (2026-11-01), 업무 규칙은 정책서 R53~R58(가정·검수 필요)

## 맥락
mvp.md 5장의 "심사 상세(서류 징구, 자동 거절 규칙, 중개수수료 정산), 플랫폼 접수"와 "신용조회, 본인인증"을
origination 모듈에 붙인다. 기존 한 줄기(신청 → 실행 → Outbox → 계약 생성)는 그대로 둔다.

## 결정

### 모듈 경계
| 문제 | 결정 |
| --- | --- |
| origination → integration(포트)을 쓰면 integration → payment·servicing → origination 순환 | 가상계좌 웹훅 컨트롤러를 payment 모듈로 옮겼다(URL·동작 동일). integration은 포트 + Mock 어댑터만 가진 말단 모듈 |
| 내부 불량 이력·완제일은 servicing 정보지만 origination → servicing도 순환 | origination에 `ServicingHistory` 인터페이스를 두고, 두 모듈을 모두 아는 operation이 `LoanAccounts`로 구현 |
| 신용조회에는 식별번호 원문이 필요 | `Customers.residentNoForCreditInquiry(id, consentRef)`: 동의 참조 필수, 호출마다 접근 기록(`CREDIT_INQUIRY_ID_USE`) |
| 심사자 후보 목록 | `StaffDirectory.activeByRole(role)` 추가 (사용 중·잠기지 않은 직원) |

### 포트 (integration, 공개 인터페이스 + internal Mock)
| 포트 | Mock 동작 |
| --- | --- |
| `CreditBureauPort` | 식별번호 끝자리 = 등급(0 → 10등급), 9·0이면 현재 연체. 보유 대출 건수 = 끝자리 % 4 |
| `IdentityVerificationPort` | 가짜 인증 화면(`/mock-identity/{tx}`)에서 이름·생년월일 입력 후 성공·실패 선택. 결과는 거래당 1회만, 10분 만료, 돌아올 주소는 같은 사이트 경로만 |
| `StoragePort` | 로컬 디스크(`arche.storage.dir`), 파일명은 UUID. 업무 테이블에는 키·크기·SHA-256·원본 이름만 |
| `LoanPlatformPort` | 플랫폼 코드별 어댑터(전략), 수신 API 키 인증 + 결과 통보. Mock은 통보를 메모리에 보관, 참조가 `FAIL`로 시작하면 전달 실패 |

### 외부가 부르는 API
세션·CSRF가 없는 `/api/integration/**`(SecurityConfig가 이미 permitAll)에 두고 각자 인증한다.
| 채널 | 경로 | 인증 |
| --- | --- | --- |
| 중개업자 | `POST /api/integration/broker/applications`, `GET …/{applicationNo}`, `POST …/{applicationNo}/documents/{docType}` | `X-Broker-Key`(SHA-256 해시 대조) + 허용 IP + 상태 ACTIVE |
| 플랫폼 | `POST /api/integration/platform/{code}/limit-inquiries`, `POST …/applications`, `GET …/applications/{platformRef}` | `X-Platform-Key` |
| 고객 랜딩 | `GET/POST /api/integration/landing/{token}/…` (identity, identity/result, consents, documents/{docType}, complete) | 일회용 토큰(32바이트, 해시만 저장) + 본인인증 |
| Mock 기관 화면 | `/api/integration/mock-identity/{tx}`, `/api/integration/mock-platform/notices` | Mock 전용 |

모든 채널 요청은 `ApplicationIntakeCommand` 하나로 정규화하고(guide 2.1), `(intake_source, external_ref)` 유니크로 재전송을 막는다.
요청 DTO의 `toString`은 개인정보를 찍지 않는다.

### 직원 API (추가)
- `GET /api/applications?status=`, `GET /api/applications/{id}/review`(신청+고객 마스킹+규칙 결과+동의+신용조회+서류)
- 동의·신용조회: `POST …/{id}/consents`, `POST …/{id}/consents/{cid}/withdrawal`, `POST …/{id}/credit-inquiries`
- 서류: `GET …/{id}/documents`, `POST …/{docId}/file`(multipart), `…/paper`, `…/verify`, `…/reject`, `GET …/{docId}/file`(접근 기록)
- 배정: `POST /api/applications/{id}/transitions {to: ASSIGNED, underwriterId?}`, `POST …/{id}/assignment`(재배정), `GET /api/underwriters`, `POST /api/underwriters/{staffId}/profile`
- 채널: `GET …/{id}/channel`, `POST …/{id}/landing-links`, `POST …/{id}/platform-notices/resend`
- 중개업자: `GET/POST /api/brokers`, `GET/POST /api/brokers/{id}`, `POST …/{id}/api-key`, `POST …/{id}/rates`, `GET /api/brokers/commissions`, `POST /api/brokers/commissions/clawback-scan`, `POST /api/brokers/commissions/applications/{appId}/mis-selling`
- `GET /api/document-requirements`

### 권한
- `application.assign`(수동 배정·재배정·심사자 프로필), `broker.manage`(중개업자·수수료율·환수): 심사팀장·본부장. 9.1 표에 행 추가, `PermissionMatrixTest` 갱신
- 중개업자 조회는 `application.review` 또는 `broker.manage`

### 스키마 (V12, V13)
- `loan_application` + channel, intake_source, external_ref, assigned_at, decided_on, reject_reason_code, broker_id
- `application_consent`, `credit_inquiry`, `screening_result`(규칙별 1행), `document_requirement`(상품별 시드), `application_document`, `underwriter_profile`, `identity_verification`
- `broker`, `broker_commission_rate`(EXCLUDE gist로 기간 겹침 금지), `broker_commission`(추가 전용, 환수는 음수 행), `platform_inquiry`(식별번호는 블라인드 인덱스만), `platform_result_notice`
- 해시 컬럼은 `VARCHAR(64)`(Hibernate validate가 `bpchar`를 별도 매핑해야 해서 CHAR 대신)

### 기타 기술 결정
- 배정 동시성: `pg_advisory_xact_lock`으로 배정 트랜잭션 직렬화 (Redis 락 대신, ADR 0001)
- 배정 전략: `AssignmentStrategy`(least-loaded 기본, round-robin) — `arche.origination.assignment-strategy`
- 결과 통보: `ApplicationDecided` 이벤트를 `@ApplicationModuleListener`로 커밋 후 비동기 처리 (발행 기록으로 유실 방지)
- 서류 형식 검사: Content-Type + 파일 첫 바이트(PDF·JPEG·PNG)
- 자동 배정 실패를 예외로 알리면 바깥 트랜잭션이 rollback-only가 되므로 `Optional`로 돌려준다
- 조기 완제 환수 점검: `@Scheduled` 23:30 + ShedLock(`brokerClawbackScan`), 수동 실행 API

## 결과·확장 지점
- 내부 불량 이력 질의는 열린 계약 전체를 훑는다(MVP 규모). 커지면 servicing에 고객별 조회 API를 추가
- Mock 본인인증 거래는 단일 서버 메모리에 둔다. 다중 서버·실제 기관 연계 시 기관 콜백 + DB 보관으로 교체
- 신용조회 결과 재사용(일정 기간 캐시)·IntegrationLog 공통 테이블·Resilience4j 재시도는 넣지 않았다
- 수수료 실제 지급(출금)·상계, 중개업자 화면에서의 직접 조회(포털)는 범위 밖
