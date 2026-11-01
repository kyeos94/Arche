# ADR 0009. 보고서·회계 분개·엑셀 다운로드 (reporting 모듈)

- 상태: 채택 (2026-11-01)
- 관련 규칙: 정책서 R24·R27·R28, R65~R68, 9.1 "엑셀 다운로드"·"보고서 조회"

## 맥락
mvp.md 5장의 "보고서(일보 상세, 연체 전이율, 대손충당금), 회계 분개 → reporting 모듈 + JournalMapper"와
guide 13.1의 목록 엑셀 다운로드(사유 입력, 접근 기록)를 구현한다. 마감된 날의 일보는 바뀌면 안 되고(R28),
reporting은 다른 모듈의 Repository를 읽을 수 없다.

## 결정

### 모듈 경계
- 새 Modulith 모듈 `dev.arche.reporting`. 공개 API는 `DailyReports`(일마감 훅) 하나이고 나머지는 `internal`.
- 원천 데이터는 소유 모듈의 읽기 전용 공개 API로만 받는다.
  - servicing: 새 인터페이스 `ServicingReports` — 처리일 기준 원장 거래(역분개는 원 거래유형 포함, 양수 거래는 양수 시점 원금·이자·비용 분해),
    계약별 잔액 스냅샷, 계약 목록 순회(목록 화면과 같은 검색 조건·데이터 범위). 구현은 servicing 안에서 `JdbcClient`로 자기 테이블을 읽는다
    (기존 `LoanAccountRepository`·`LoanController`를 건드리지 않아 다른 작업과 충돌을 줄임).
  - payment: 기존 `Payments.totalsFor`, servicing: 기존 `LoanAccounts.correctionsPostedOn`(R28 정정), customer: `Customers.get`(마스킹된 값).
- 일마감 연결: operation의 `DailyClosingService`가 ⑤ 대사 직후, 마감 기록·⑥ 업무일자 변경 전에 `DailyReports.snapshot(D)`를 직접 호출한다.
  Modulith 이벤트(커밋 후 비동기)로 하면 업무일자가 먼저 넘어가 R24 순서와 "마감 시점 잔액"이 보장되지 않기 때문이다. 의존 방향은
  operation → reporting → servicing·payment·customer·staff·calendar로 순환이 없다.

### 저장 (Flyway V16)
| 테이블 | 내용 |
| --- | --- |
| `report_daily`, `report_daily_line` | 일보 헤더(분개 차변·대변 합계, 미매핑 건수) + (구역, 항목, 건수, 금액) 행. 항목을 행으로 두어 새 거래유형·상태가 생겨도 스키마를 바꾸지 않음 |
| `report_journal_line` | 마감일의 계정별 차변·대변 합계 (매핑을 나중에 바꿔도 과거 일계 유지, R68) |
| `report_month_end`, `report_month_end_account` | 월마감: 말일 마감 직후 계약별 상태·구간·잔액 (R66) |
| `provision_rate` | 구간별 적립률 이력 (R67) |
| `journal_account`, `journal_rule`, `journal_component_account` | 자체 계정과목표, 거래유형별 분개 방식(BOOK / SETTLE)과 상대 계정, 충당 항목별 계정 (guide 6.1 "매핑은 설정 테이블") |

- 보고서 테이블은 집계 결과를 한 번에 쓰는 추가 전용 성격이라 JPA 엔티티 대신 `JdbcClient`로 읽고 쓴다(엔티티가 없으므로 ddl-auto=validate 대상 아님).
- 스냅샷은 마감 기록이 없을 때만 다시 쓴다(마감 실패 후 재실행). 마감된 날은 operation이 먼저 막는다(`ALREADY_CLOSED`).

### JournalMapper
- 순수 함수(Spring·DB 무관): 원장 거래 1건 + 규칙 → 분개 행들. 거래마다 차변 = 대변이 되도록 "항목은 정해진 계정, 나머지(잔차)는 상대 계정"으로 만든다.
  - BOOK(실행·양수·비용 적재·환매): 금액을 자산 계정 차변, 상대 계정 대변. 양수는 원금·양수 전 이자·비용으로 나눔.
  - SETTLE(그 밖): 충당 항목(원금, 정상·가산이자, 비용, 수수료, 양수 전 이자, 가수금 증감, 반환 대기)을 각 계정 대변, 잔차를 상대 계정 차변.
    입금은 잔차 = 받은 돈(현금), 가수금 충당·중도상환은 잔차 0, 감면은 잔차 = 감면액(감면손실).
  - 확정 이자(normalAccrued·lateAccrued)는 차변 미수이자 / 대변 이자수익 (R1).
  - 역분개는 원 거래유형 규칙에 음수 금액을 넣고, 음수 차변은 대변으로 옮긴다.
  - 규칙이 없는 유형(예: 다른 작업이 추가할 SETOFF)은 SETTLE + 상대 계정 `X-SUSPENSE`(미결산)로 만들고 미매핑 목록에 올린다. 문자열로 다루므로
    `PostingType` enum에 없는 값도 깨지지 않는다.
- 계정 코드는 `A-`(자산) `L-`(부채) `I-`(수익) `E-`(비용) `X-`(미결산) 접두어의 자체 코드다. 실제 회계 기준·기관의 코드 체계를 쓰지 않는다.

### 엑셀 다운로드
- 라이브러리: 이미 쓰고 있는 Apache POI `poi-ooxml` 5.5.1에 포함된 **SXSSF**(`SXSSFWorkbook`, 메모리 창 100행)로 스트리밍한다. 새 의존성은 없다.
  계약 목록은 500건씩 키셋 페이지로 읽어 바로 행으로 쓰므로 건수와 무관하게 메모리가 일정하다.
- API: `POST /api/exports/loans` `{status, q, reason}`, `POST /api/exports/reports` `{kind, date|month, reason}` → xlsx 본문.
  GET이 아니라 POST인 이유: 사유를 URL·서버 접근 로그에 남기지 않고 CSRF 보호를 받기 위해. 프런트는 Blob으로 받아 저장한다.
- 응답을 쓰기 전에 권한·사유를 검사하고 `access_log`(action `EXCEL_DOWNLOAD`)를 남긴다. 검색어 원문(주민등록번호일 수 있음)은 남기지 않는다.
- 파일의 고객 이름은 가운데 글자, 주민등록번호는 뒤 6자리, 휴대폰은 가운데 4자리를 마스킹한다.

### API
| 메서드·경로 | 권한 | 내용 |
| --- | --- | --- |
| `GET /api/reports/daily?date=` | report.read | 일보 (마감일은 스냅샷, 아니면 미리보기) |
| `GET /api/reports/months` | report.read | 월마감 목록 |
| `GET /api/reports/roll-rate?month=` | report.read | 전월 말 → 당월 말 전이 행렬 |
| `GET /api/reports/provision?month=` | report.read | 구간별 대손충당금 기초 집계 (month 없으면 현재) |
| `GET /api/reports/journal?date=` | report.read | 일자별 분개, 계정별 합계, 차변 = 대변 검사, 미매핑 |
| `POST /api/exports/loans` | excel.download | 계약 목록 엑셀 |
| `POST /api/exports/reports` | excel.download | 보고서 엑셀 |

## 결과
- 새 권한 `report.read`(`Permission.REPORT_READ`) — 정책서 9.1 "보고서 조회" 행, `PermissionMatrixTest`에 반영.
- 대량 다운로드 임계치 알림(guide 8.2)은 접근 기록만 남기고 알림은 확장 지점으로 둔다.
- 다른 작업이 상각·매각 등 새 상태·거래유형을 추가하면 구간은 이름 규칙(R66)으로, 분개는 `journal_rule` 행 추가(없으면 미결산)로 흡수한다.
  V16은 CHARGE_OFF·SALE·REPURCHASE·ADJUSTMENT 규칙을 미리 넣어 둔다(금액 필드 의미가 SETTLE 방식과 다르면 그 작업에서 규칙만 고친다).
