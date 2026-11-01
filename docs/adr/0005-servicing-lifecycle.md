# ADR 0005. 채권 생애주기 — 상각·매각·환매·수기 조정·상계·상태 되돌리기·추심 중지

- 상태: 채택 (2026-11-01), 업무 규칙은 정책서 R39~R46 (가정·검수 필요)

## 맥락
mvp.md 5장의 범위 밖 기능 중 “상각 확정, 매각·환매, 매각 후 입금 이전”, “수기 조정 거래, 상계, 완제·매각 후 취소”,
“사망(상속), 개인회생·파산, 공적 채무조정”을 구현한다. 확장 지점은 “LoanAccount 상태 전이 + 원장 CHARGE_OFF·SALE 거래”,
“원장 ADJUSTMENT 거래 + 결재”, “collection 모듈의 추심 중지 플래그 + 금리 이력 변경”이었다.

## 결정

### 1. 상태를 직접 바꾸지 않고 재생 이벤트로
상각·매각·환매·양수인 이전·수기 조정·상계·이자 부과 중단을 모두 `account_event` 유형으로 추가하고 엔진(`LoanSimulator`)이 재생한다.
`LoanAccount.status`는 여전히 재생 결과의 스냅샷이다(CHARGED_OFF·SOLD 추가).

- 되돌리기(R43)는 “이벤트 status = CANCELLED 후 처음부터 재생” 하나로 끝난다. 상각 기간에 붙지 않았던 이자도 재생이 되살리고,
  달라진 원장 거래는 기존 방식대로 REVERSAL + 새 거래가 된다 (ADR 0002). 상태별 되돌리기 코드를 따로 두지 않는다.
- 소급 입금·입금 취소가 상각·매각 이전으로 들어와도 같은 재생으로 상각액·매각액이 다시 계산된다.

| 이벤트 | 하루 중 처리 순서 | 원장 |
| --- | --- | --- |
| REPURCHASE → CHARGE_OFF → SALE | 하루 시작(그날 입금보다 먼저) | REPURCHASE, CHARGE_OFF, SALE |
| SETOFF_IN | 입금과 같은 자리 | SETOFF (즉시 충당 또는 가수금) |
| ADJUSTMENT, SETOFF_OUT, BUYER_TRANSFER | 하루 끝 | ADJUSTMENT, SETOFF, SALE_TRANSFER(음수) |
| INTEREST_SUSPENSION [from, end) | 이자 기준금액 함수에서 0 | 없음 (이자만 줄어듦) |

### 2. 원장 표현
- `CHARGE_OFF`는 장부 제거액 메모: 항목 금액에 제거한 원금·이자·비용을 담지만 청구 잔액은 줄이지 않는다(채권 존속).
  그래서 일마감 대사(`sumPrincipalRepaid`)에서 CHARGE_OFF와 그 역분개를 뺀다.
- `SALE`은 실제로 잔액을 넘기므로 원금 감소에 넣고(잔액 0), `REPURCHASE`는 음수로 되살린다.
- `SALE_TRANSFER`(새 유형): 매각 후 입금의 양수인 이전 대상(부채). 금액 양수 = 증가, 음수 = 이전 완료·환매 해제.
  잔액 컬럼은 따로 두지 않고 `SimulationResult.transferPending`을 `loan_account.transfer_pending`에 스냅샷으로 둔다.
- `SETOFF`(새 유형): 원천 계약은 refund/suspense 음수, 대상 계약은 입금처럼 충당 항목.
- `ADJUSTMENT`는 기존 의미(양수 = 감소, 음수 = 증가)를 그대로 써서 수기 조정의 비용 증액은 cost 음수.

### 3. 결재 테이블 `adjustment_request`
수기 조정·상계·상각 취소·매각 취소·상계 취소·완제 취소를 한 테이블(type 컬럼)로 둔다. 요청은 잔액을 바꾸지 않고, 승인 시
검증을 다시 한 뒤 이벤트를 만든다. 작성자 ≠ 승인자는 서비스에서 검사(ADR 0004와 같은 방식).
완제 취소는 재생할 이벤트가 없으므로 “R31 해제권” 한 장으로 표현한다: APPLIED 상태가 있으면 `cancelPayment`가 1건 허용하고 CONSUMED로 바꾼다.
기존 입금 취소·정정(R27·R28) 절차와 권한은 그대로 쓴다. R31 판정은 payment 모듈에서 servicing의 `cancelPayment`(계약 락 안)로 옮겼다.

### 4. 매각 기록 `loan_sale`
양수인·매각가·양도 통지 발송일·환매 이력은 금액 효과가 없는 업무 기록이라 별도 테이블. 양수인 이름은 자유 입력(실제 기관 코드 체계 없음).
실제 송금(양수인 이전)은 출금 확장 지점으로 남기고, 여기서는 “이전 완료” 금액만 기록한다.

### 5. 추심 중지 (collection 모듈)
`collection_stop`(V9)에 고객 단위 사유를 계약마다 한 행씩(같은 group_no) 남긴다. 판정은 `ContactService.decide`와
`AutoMessageService.dispatch`의 기존 판정 사슬에 끼운다(R45 순서). 이자·금리 효과는 servicing 공개 API
(`suspendInterest`·`endInterestSuspension`·`lowerRate`)로만 요청한다(다른 모듈 Repository 미참조).
매입채권은 엔진이 금리 이력을 보지 않았으나, 양수일 뒤의 이력은 적용이율을 바꾸도록 `LoanTerms.rateAt`을 넓혔다(기존 golden 영향 없음).

### 6. API·화면
- `GET /api/loans/charge-off-candidates`, `POST /api/loans/{id}/charge-off|sale|repurchase|buyer-transfers` (본부장, `loan.chargeoff.confirm`)
- `GET /api/loans/{id}/lifecycle`: 상각·매각 상태, 이전 대상, 상계 내역, 결재 요청 (상세 DTO를 늘리지 않으려고 따로 둠)
- `POST /api/adjustment-requests`, `GET /api/adjustment-requests?status=`, `POST /api/adjustment-requests/{id}/decision`
- `GET|POST /api/loans/{id}/collection-stops`, `POST /api/loans/{id}/collection-stops/{stopId}/release|confirm-relief`
- 화면: 계약 상세의 “생애주기” 탭(상각·매각·환매·이전, 조정·상계·되돌리기 요청), 추심 탭의 추심 중지 카드,
  메뉴 “상각 후보”(본부장)와 “조정 결재”(채권팀장·본부장)

## 결과
- golden G1~G28은 그대로 통과. 새 규칙은 `LifecycleRuleTest`(엔진)·`LifecycleServiceTest`·`LifecycleIT`·`CollectionStopIT`로 검증.
- 이벤트 유형 CHECK 제약(`account_event_type_check`, `ledger_entry_type_check`)을 다시 만들었으므로, 같은 제약을 고치는
  다른 마이그레이션과 합칠 때는 값 목록을 합쳐야 한다.
- 상각 후보는 월말 배치 대신 조회 화면(같은 조건)이다. 월말 스냅샷·대손충당금 연계는 reporting 확장 지점.
