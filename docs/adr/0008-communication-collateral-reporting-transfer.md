# ADR 0008. 연락(문자·팩스·우편)·담보·신용정보 보고·출금(펌뱅킹)

- 상태: 채택 (2026-11-01), 업무 규칙은 정책서 R59~R64(가정·검수 필요)

## 맥락
mvp.md 5장의 범위 밖 기능 중 "문자·팩스·우편, 연락 타임라인 패널", "담보·보증인 관리, 담보 시세 연계",
"신용정보 보고(정보집중), 펌뱅킹 송금"을 구현한다. 같은 시기에 다른 작업이 servicing 엔진·payment 충당을 고치고 있어,
기존 모듈 수정은 공개 API 추가 정도로 줄인다.

## 결정

### 모듈

| 모듈 | 내용 | 의존 |
| --- | --- | --- |
| `integration` (기존) | 포트 `FaxPort`·`PostalPort`·`CollateralValuationPort`·`CreditReportingPort`·`TransferPort` + Mock 어댑터, `FixedLengthMapper`, 연계 요청 이력 `integration_call_log` | — |
| `communication` (신규) | 템플릿, 수동 발송, 상담 메모, 연락 타임라인 | collection(`CollectionContacts`), servicing, customer, integration |
| `collateral` (신규) | 담보(차량·부동산)·시세 이력·보증인, LTV | servicing, integration, common.crypto |
| `creditinfo` (신규) | 신용정보 보고 파일 생성·전송·결과 반영 | servicing, customer, integration |
| `treasury` (신규) | 출금(작성·승인·송금·결과 조회) | payment(`UnmatchedRefunds`), servicing(`RefundPayouts`), integration |

- mvp.md는 담보·보증인을 "servicing 모듈의 하위 엔티티"로 적었지만 별도 `collateral` 모듈로 뺐다. 담보는 계약 금액 계산(엔진)에
  영향을 주지 않고, servicing을 동시에 고치는 작업과 충돌을 피하기 위해서다. 다른 모듈이므로 `loan_account` FK 없이 ID만 둔다(guide 16.4).
- 추심 연락 판정(7일 7회·유예·시효·시간)은 collection에 그대로 두고 공개 API `CollectionContacts`로 연다. communication은 판정을
  다시 구현하지 않는다. 팩스·우편 추심 안내를 한도에 세기 위해 `ContactQuotaGuard.Channel`에 `FAX`·`POST`를 추가했다(V14).
- 출금이 계약의 돈을 바꾸는 경우(완제 후 과입금 반환)는 원장을 직접 쓰지 않는다. servicing에 추가 전용 `refund_payout`
  (예약 +, 해제 −)을 두고, 반환 가능액 = 엔진이 계산한 반환 대기액 − 예약 합계로 본다. 엔진 이벤트를 늘리지 않은 이유:
  재생 결과와 원장 비교(ADR 0002)에 새 거래 유형이 끼면 다른 작업과 충돌하고, 과입금 반환은 이자·충당에 영향이 없다.
  미확인 입금 반환은 payment의 `UnmatchedRefunds`가 `unmatched_deposit.status`를 OPEN ↔ REFUNDED로 바꾼다(조건부 UPDATE).

### 연계·파일
- 모든 Mock 어댑터는 `CallLogger`로 `integration_call_log`(기관, 업무, 요약, 소요시간, 결과코드, 성공 여부)를 별도 트랜잭션으로 남긴다.
  요약에는 팩스 번호·계좌번호를 마스킹해 넣고, 고객은 ID로만 적는다.
- Mock 파일은 `arche.integration.files-dir`(기본 `backend/build/integration-files`) 아래에 둔다: `fax/`, `postal/`,
  `credit-report/outbound|sent|inbound/`.
- 신용정보 보고 레이아웃은 자체 설계한 일반형 120바이트 고정길이(MS949, 레코드마다 CRLF): H 헤더 / D 데이터 / T 트레일러,
  결과 파일은 R 레코드. 사유 코드(01 개설·02 연체 등록·03 연체 해제·04 종료), 결과 코드(0000·E101·E201·E301)도 자체 정의다.
  실제 기관 형식·코드 체계가 아니다.
- `FixedLengthMapper`는 record 구성 요소의 `@FixedLengthRecord.Field(offset, length)`로 레이아웃을 선언한다. 길이는 바이트 기준이고,
  문자열은 문자 경계에서 자르며(한글을 반으로 쪼개지 않음), 숫자가 자리수를 넘거나 음수면 오류(금액을 잘라 보내지 않음).
- Mock 송금은 계좌 끝자리로 시나리오를 주입한다(9 실패, 8 타임아웃 → 조회 성공, 7 타임아웃 → 조회 실패, 6 예금주 불일치).
  결과 조회는 원 요청(멱등 키·계좌)으로 하므로 서버가 다시 떠도 같은 답이 나온다.
- Mock 등기 배달은 접수 후 경과일 + 등기번호 해시로 진행한다(1일째 배송 중, 2~3일째 배달, 해시가 10의 배수면 반송).

### 테이블 (V14·V15)
- V14: `integration_call_log`, `message_template`(+기본 템플릿 5개), `communication_dispatch`, `contact_memo`,
  `collateral`, `collateral_valuation`, `guarantor`, `contact_log.channel` CHECK에 FAX·POST 추가
- V15: `credit_report_file`, `credit_report_record`, `credit_report_state`, `withdrawal`, `refund_payout`
- 보증인 식별번호·연락처, 팩스 번호, 받는 계좌번호는 `EncryptedStringConverter` 암호문. 보증인 식별번호는 블라인드 인덱스로
  같은 계약의 중복 등록을 막는다(부분 유니크 인덱스).

### API·화면
- `GET /api/loans/{id}/timeline?page&size` 연락 타임라인(연락 기록·자동 문자·수동 발송·메모를 시각순으로 합침, 같은 연락의 중복은 연락 기록 쪽을 뺌)
- `POST /api/loans/{id}/dispatches`, `POST /api/loans/{id}/dispatches/{did}/track`, `POST /api/loans/{id}/memos`,
  `GET /api/loans/{id}/templates/{tid}/preview`, `GET|POST /api/message-templates[/{tid}]`
- 계약 상세 우측 패널 = 타임라인 + 빠른 입력(전화·방문 기록은 기존 `/contacts`, 문자·팩스·우편은 `/dispatches`, 메모), 남은 연락 횟수 표시
- 권한: 발송·메모 `collection.activity`, 템플릿 관리 `admin.manage`(정책서 9.1 "코드 관리"), 조회 `loan.read`

## 결과
- 실제 기관 연계 시 같은 포트의 다른 어댑터로 바꾸면 된다(`@ConditionalOnProperty`).
- 타임라인은 출처별 조회 후 메모리에서 합쳐 페이지를 나눈다. 계약 1건의 연락 이력은 수백 건 수준이라 충분하며, 커지면 UNION 쿼리로 바꾼다.
