# ADR 0006. 일괄 입금 배분·대위변제·법조치 상세

- 상태: 채택 (2026-11-01, 업무 규칙 R47~R52는 가정·검수 필요)

## 맥락
mvp.md 5장 범위 밖 기능 중 "한 고객의 여러 계약 일괄 입금 배분, 보증인·제3자 대위변제"(payment 모듈의 배분 전략 인터페이스),
"법조치 상세(지급명령·압류·경매), 경매 배당금·공탁금 입금"(LegalAction 엔티티 + 비용 충당)을 구현한다.
계산 엔진(`servicing.engine`)과 golden 결과(G1~G28)는 바꾸지 않는 것이 전제다.

## 결정

### 일괄 입금 배분 (R47·R48)
- `payment.internal.split.AllocationStrategy`: 순수 함수 인터페이스 + 구현 3종(연체 우선·잔액 비례·수동 지정). 배분 결과는 금액만 정하고,
  몫마다 기존 `LoanAccounts.applyPayment`로 일반 입금과 똑같이 처리한다. 재생·기산일(R13)·충당(4장) 로직을 새로 만들지 않는다.
- 배분 기준은 servicing의 새 API `customerAccounts(customerId)`가 업무일자로 재생한 `DueSnapshot`(연체금·다음 예정액·완제 금액).
  락 없이 계산한 추정치이며, 실제 충당은 계약 락 안에서 엔진이 다시 계산한다.
- 저장 모델: 원 입금 `payment`(status `SPLIT`, account 없음, `split_method`) + 계약별 하위 입금(`parent_payment_id`, 거래고유번호 `원번호-S{n}`).
  일보 집계(`ALLOCATED` 합계)는 하위 입금만 세므로 이중 집계가 없다. `bank_tx_id`를 60자로 늘렸다.
- 취소: 원 입금에 대한 `PaymentCorrection`(CANCEL) 1건. 하위 입금마다 R26 취소 후 하위 입금은 `CANCELLED`(돈은 원 입금에 있음),
  원 입금은 `UNMATCHED` + 새 `unmatched_deposit`. 정정 유형 CHECK 제약을 바꾸지 않으려고 새 유형을 만들지 않고 "SPLIT 상태의 CANCEL"로 구분한다.
- 계약 락 순서는 계약 id 오름차순(교착 방지).

### 입금자 구분·대위변제 (R49·R50)
- `payment.payer_type`(CUSTOMER/GUARANTOR/THIRD_PARTY), `payer_name_enc`(EncryptedStringConverter), `payer_ref`(자유 메모).
  보증인 관리(다른 슬라이스)와 결합하지 않으려고 보증인 엔티티 id 대신 자유 메모로 둔다.
- `subrogation_claim`: 보증인·제3자 입금마다 1건(입금당 ACTIVE 1건, 부분 유니크 인덱스). 취소·이동·금액 정정을 `PaymentService`의 공통 `apply()`/`cancelClaim()`이 따라간다.
- 시효 미반영(R50): servicing에 `excludeFromLimitation(accountId, paymentId)`를 추가하고 `account_event.limitation_excluded`로 표시.
  재생 입력은 그대로라 이자·충당에는 영향이 없다. `LimitationService`만 이 플래그를 읽는다.
- 화면·API는 이름을 마스킹(`홍*동`)해서만 내보내고, `Payer`·요청 레코드의 `toString`은 평문을 쓰지 않는다.

### 법조치 (R51·R52)
- collection 모듈: `legal_action`(현재 단계), `legal_action_stage`·`legal_action_cost`(추가 전용, `@Immutable`). 법원·사건번호·대상은 자유 입력 문자열이며 형식을 검사하지 않는다(실제 기관 코드 체계를 넣지 않음).
- 단계 전이·시효 연결 규칙은 순수 클래스 `LegalActionRules`로 두고 단위 테스트한다.
- 시효 연결: servicing API `recordLimitationEvent`(기존 `limitation_event`에 기록, id 반환)와 `voidLimitationEvent`(`limitation_event.voided_on`·`void_reason`, 행은 남김).
  신청 단계 행이 `limitation_event_id`를 들고 있어 취하·각하 시 그 기록을 무효로 한다.
- 비용: 기존 `LoanAccountService.chargeCost`를 `LoanAccounts` 공개 API로 올려 그대로 쓴다(COST 이벤트 → 재생). 별도 비용 원장을 만들지 않는다.
- 배당금·공탁금: `Payments.Channel`에 `AUCTION_DISTRIBUTION`, `COURT_DEPOSIT` 추가, `payment.legal_action_id`(모듈 간 FK 없음).
- 모듈 순환: payment → collection을 참조하면 collection → integration → payment → collection 순환이 생겨 `ApplicationModules.verify()`가 실패한다.
  payment에 `LegalActionLink` 포트(법조치 → 계약 id)를 두고 collection이 구현해 의존 방향을 뒤집었다.

### 권한
- 새 권한 코드는 만들지 않았다. 배분·입금자 구분은 `payment.register`(입금 등록), 법조치 등록·단계·비용은 R37의 `collection.legal`(채권팀장·본부장),
  조회는 `loan.read` + 데이터 범위(담당 건/팀 전체). 9.1 표는 바뀌지 않는다.

### API
| 메서드 | 경로 | 권한 |
| --- | --- | --- |
| GET | `/api/payment-splits/targets?customerId=` 또는 `?accountNo=` | payment.register |
| POST | `/api/payment-splits/preview` | payment.register |
| POST | `/api/payment-splits` (새 입금 또는 `unmatchedId`) | payment.register |
| GET | `/api/payment-splits/{parentPaymentId}` | payment.register 또는 loan.read |
| GET | `/api/subrogations?accountId=` | loan.read |
| GET | `/api/payments?parentPaymentId=` / `?legalActionId=` (기존 목록에 필터 추가) | 기존과 같음 |
| POST | `/api/payments` 본문에 `payerType`·`payerName`·`payerRef`·`legalActionId`·`channel` | payment.register |
| GET/POST | `/api/loans/{id}/legal-actions` | loan.read / collection.legal |
| POST | `/api/legal-actions/{id}/stages`, `/api/legal-actions/{id}/costs` | collection.legal |
| GET | `/api/legal-actions?openOnly=` | loan.read |

### 스키마
- V10: payment 채널·상태 CHECK 교체, 입금자·배분·법조치 컬럼, `subrogation_claim`, `account_event.limitation_excluded`, `limitation_event.voided_on`.
- V11: `legal_action`, `legal_action_stage`, `legal_action_cost`, `legal_action_no_seq`(번호 접두어 `LA`).

## 결과
- golden G1~G28과 엔진 코드는 그대로다(servicing 변경은 공개 API 추가와 시효 계산 필터뿐).
- 위험: V10은 `payment_channel_check`·`payment_status_check`를 지우고 다시 만든다. 다른 작업이 같은 제약에 값을 추가하면 병합 시 합쳐야 한다.
- 확장 지점: 대위변제자에게 담보·서류 이전, 과입금 반환 출금, 법조치 비용 취소(수기 조정), 법원 기일 알림, 배분 기준 스냅샷 보관.
