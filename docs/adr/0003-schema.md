# ADR 0003. 스키마 — guide 16.2~16.4와 다른 점

- 상태: 채택 (2026-10-31)

기준은 guide.md 16.2~16.4 표다. 아래만 다르게 정했다.

| 항목 | guide | Arche | 이유 |
| --- | --- | --- | --- |
| 재생 입력 | (없음) | `account_event` (PAYMENT, PREPAYMENT_REQUEST, DUE_DAY_CHANGE, COST_CHARGE) | servicing은 payment 모듈의 Repository를 읽을 수 없다. 엔진이 재생할 입력을 servicing이 직접 보관하고, 취소는 status만 바꾼다 (ADR 0002) |
| 원장 원 이벤트 참조 | paymentId | `source_ref`(“EV-{이벤트 id}”, “DUE-{날짜}” 등) + paymentId | 재계산 결과와 저장된 거래를 키(유형·원 이벤트·기산일)로 비교하기 위해 |
| `LedgerEntry` 추가 컬럼 | — | `business_date`, `refund`, `normal_accrued`, `late_accrued` | 처리일 일보(R28), 과입금 반환 대기, 정산 이벤트에서 확정된 이자(R1)를 원장에 남기기 위해 |
| `LedgerAllocation.installmentId` | Installment FK | `installment_seq` | 회차는 스케줄 버전마다 행이 따로 있어(재산출 시 REPLACED) FK가 버전에 묶이면 재계산 비교가 복잡해진다 |
| `LoanAccount` 요약 컬럼 | principalBalance 등 | + `accrued_*`, `payoff_amount`, `delinquency_days`, `next_due_date`, `as_of`, `virtual_account_no`, `application_id`, `product_code` | 목록·요약 바를 재생 없이 보여주기 위한 스냅샷. 일마감 ⑤에서 원장과 대사 |
| `LoanAccount.virtualAccountNo` | (integration 모듈) | loan_account 컬럼 | 가상계좌는 계약당 1개라 별도 테이블 없이 둔다 |
| 공통 컬럼 | 모든 테이블 | 원장·접근기록·상태이력·일마감은 `created_at`/`created_by`만 | 추가 전용 테이블은 수정이 없으므로 `updated_at`·`version`이 의미 없다 |
| 원장 수정 금지 | 규칙 | DB 트리거 `forbid_ledger_mutation` + `@Immutable` | 코드 실수로도 UPDATE·DELETE가 불가능하게 |
| superseded | 뷰로 계산 | `ledger_entry_effective` 뷰 | guide 그대로 |
| `business_date.current_date_` | currentDate | 컬럼명 끝 `_` | `current_date`는 PostgreSQL 예약어 |
| 번호 | — | `customer_no_seq` 등 시퀀스, 접두어 C·L·A·PC | 외부 노출 번호를 PK와 분리 (guide 16.1) |
| `prepayment_request` | 엔티티 | 테이블만 두고 미사용 | 중도상환 요청은 `account_event`로 재생하고, 견적은 `/quote`가 매번 계산. 견적 보관은 확장 지점 |
