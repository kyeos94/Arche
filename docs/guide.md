# 대부 여신관리 시스템 Spring 구현 가이드

Oct 30, 2026 · @김연서

대출 신청부터 심사·실행·회수·종결까지 다루는 여신관리 시스템을 Java 21 + Spring Boot 3로 설계·구현하기 위한 가이드입니다. 모든 내용은 공개된 법령·업계 관행·일반 설계 패턴을 바탕으로 새로 작성했습니다.

> **기준 문서 안내**: 이 탭은 일반적인 설계 원칙입니다. 구체적인 수치와 업무 규칙은 ‘가상 회사 정책서’ 탭이 기준이고, 기대값은 ‘계산 검증 사례’ 탭이 기준입니다. 이 탭의 일반론(연 일수 선택지, 은행 약관 예시, 주택담보 기준, 알림 시점 등)과 정책서가 다르면 정책서를 따릅니다.

## 0. 작성 원칙

이 문서는 특정 회사의 소스·설계·내부 규정을 참조하지 않고, 누구나 접근 가능한 자료만으로 작성합니다.

### 0.1 출처로 삼는 것

- 법령·고시: 대부업법, 민법, 상법, 신용정보법, 개인정보 보호법, 금융소비자보호법과 그 하위 규정
- 공개 표준: 공정위·금융위 표준약관, 금융권 공통 상환방식 계산식, 금융상품 공시 자료
- 일반 기술: Spring 공식 문서, 도메인 주도 설계(DDD), 이력 모델링·감사 로그 등 업계 공통 패턴

### 0.2 배제하는 것

| 배제 대상 | 이유 | 문서에서의 대체 |
| --- | --- | --- |
| 실제 테이블·컬럼·클래스·화면 이름 | 회사 설계의 직접 복제 | 영문 도메인 용어로 새로 명명 |
| 회사 고유의 업무 규칙 (상품 코드, 내부 기준값, 분배 규칙, 상태 코드 체계) | 영업비밀 | 법령·표준약관 기준의 일반 규칙 + 설정값으로 두기 |
| 암호화 대상·알고리즘·키 관리 방식의 실제 구성 | 보안 정보 | 법적 요구사항 + 표준 암호화 설계 |
| 기관 연계 전문 레이아웃·접속 정보·계약 조건 | 기관과의 계약상 비밀 | 추상 인터페이스 + Mock 구현 |
| 회사명·법인 구성·거래처·직원 정보 | 내부 정보 | 가상의 단일 회사로 설계 |
| 실제 화면 배치·디자인·스크립트 구조 | 저작물 | 일반 목록/상세 화면 패턴으로 자체 설계 |

### 0.3 구현할 때 지킬 것

- 회사 코드·문서·화면 캡처를 집에 가져가거나, 기억에 의존해 그대로 재현하지 않습니다.
- 값이 필요한 곳(금리, 기간, 횟수 등)은 법령 상한이나 임의의 예시값을 설정 파일로 둡니다.
- 법령 수치는 작성일 기준이므로, 구현 전 국가법령정보센터 원문을 다시 확인합니다.

## 1. 전체 아키텍처

단일 저장소의 모듈러 모놀리스(Spring Modulith 또는 Gradle 멀티모듈)로 시작하고, 도메인 경계는 패키지와 이벤트로 나눌 것을 권장합니다. 포트폴리오 규모에서는 MSA보다 경계 설계를 보여주는 편이 설득력이 큽니다.

&#91;embedded content: 모듈 구성 · 진입점 3개, 도메인 모듈 11개, 공통·연계 계층, 인프라 4개\]

### 1.1 기술 스택

| 영역 | 선택 | 이유 |
| --- | --- | --- |
| 언어·프레임워크 | Java 21, Spring Boot 3.x | record, sealed interface, virtual thread 활용 |
| 영속성 | Spring Data JPA + QueryDSL, Flyway | 복잡한 목록 검색은 QueryDSL, 스키마는 마이그레이션으로 관리 |
| DB | PostgreSQL | 날짜 범위 타입(daterange), 부분 인덱스 |
| 캐시·락 | Redis (Redisson) | 세션, 코드 캐시, 계약별 분산 락 |
| 배치 | Spring Batch + ShedLock | 대량 일괄 처리, 다중 서버 중복 실행 방지 |
| 비동기 | Spring Events + Outbox, 필요 시 RabbitMQ | 도메인 간 결합 완화, 외부 연계 재시도 |
| 보안 | Spring Security, 메서드 보안 | 메뉴·기능 권한 |
| 화면 | Thymeleaf + Bootstrap 5 + 바닐라 JS (또는 React) | 업무 시스템은 서버 렌더링 중심이 단순함 |
| 문서·엑셀 | Apache POI(SXSSF), OpenPDF 또는 Flying Saucer | 대용량 엑셀, 계약서 PDF |
| 테스트 | JUnit 5, Testcontainers, ArchUnit | 계산 로직 단위 테스트, 모듈 의존 규칙 검사 |

### 1.2 모듈 구성

| 모듈 | 책임 | 핵심 애그리거트 |
| --- | --- | --- |
| `origination` | 대출 신청 접수, 심사, 승인, 실행 | LoanApplication |
| `product` | 상품 정의(금리 범위, 기간, 상환방식, 수수료 정책) | LoanProduct |
| `servicing` | 계약, 상환 스케줄, 이자 계산, 원장 | LoanAccount, Ledger |
| `payment` | 입금·출금, 가상계좌 매칭, 미확인 입금 | Payment, UnmatchedDeposit |
| `collection` | 연체 관리, 추심 활동, 법조치, 소멸시효 | DelinquencyCase, LegalAction |
| `workout` | 채무조정(화해), 상각, 매각·환매 | Settlement, ChargeOff, AssetSale |
| `customer` | 고객·연락처·동의, 변경 이력 | Customer |
| `communication` | 문자·팩스·우편·메모 | Message, Memo |
| `document` | 이미지·녹취 파일, 보관·파기 | StoredFile |
| `staff` | 직원·조직·권한·접속 기록 | Staff, Role |
| `integration` | 외부 기관 연계 어댑터 | (포트 인터페이스) |
| `reporting` | 집계·보고서, 일마감 | DailyClosing |
| `common` | 금액·기간 값 객체, 암호화, 감사, 영업일 달력 | Money, DateRange, BusinessCalendar |

모듈 간 호출은 공개 API(파사드 서비스) 또는 도메인 이벤트로만 하고, 다른 모듈의 Repository를 직접 참조하지 않습니다. 이 규칙은 ArchUnit 테스트로 강제합니다.

### 1.3 공통 값 객체

- `Money`: `BigDecimal` + 통화, 원 단위 반올림 규칙(`RoundingMode`)을 한 곳에서 관리. `double`은 금지
- `InterestRate`: 연이율(%)을 `BigDecimal`로 보관, 일할 변환 메서드 제공
- `DateRange`: 시작일·종료일(반개구간 `[from, to)` 권장), 겹침·포함 검사
- `BusinessCalendar`: 영업일 테이블 기반으로 휴일이면 다음/이전 영업일 계산

## 2. 대출 신청·심사 프로세스

여러 채널에서 들어온 신청을 하나의 `LoanApplication`으로 정규화하고, 상태 머신으로 심사 단계를 관리한 뒤, 실행 시점에 여신관리(회수) 도메인의 `LoanAccount`를 생성합니다.

### 2.1 접수 채널과 정규화

| 채널 | 수신 방식 | 구현 포인트 |
| --- | --- | --- |
| 직접 신청 (홈페이지) | REST API | 본인인증 결과 토큰 검증 후 접수, 동의 이력 함께 저장 |
| 대출중개업자 | REST API + 첨부 파일 업로드 | 중개업자별 API 키·IP 허용, 중개수수료 정산 대상 표시 |
| 대출 비교 플랫폼 | 한도조회 → 신청 → 결과 통보 3단계 API | 플랫폼별 어댑터(전략 패턴), 비동기 회신 |
| 직원 직접 입력 | 사내 화면 | 상담 중 수기 등록 |

채널별 요청 DTO는 `ApplicationIntakeCommand` 하나로 변환하고, 이후 로직은 채널을 모르게 합니다. 동일 고객의 중복 신청은 고객 식별값의 블라인드 인덱스(10장)로 검사합니다.

### 2.2 심사 상태 머신

1. `RECEIVED` 접수 → 자동 검증(연령, 필수 항목, 중복)
2. `PRE_SCREENING` 사전심사 → 신용조회, 내부 불량 이력 조회, 자동 거절 규칙
3. `ASSIGNED` 심사자 배정 → 배정 전략(라운드로빈, 처리 건수 균등, 상품별 담당)을 인터페이스로 분리
4. `UNDERWRITING` 본심사 → 소득·재직 서류 징구, 담보 평가, 한도·금리 산출
5. `APPROVED` / `REJECTED` / `WITHDRAWN` → 결재권자 승인(금액별 전결 규칙), 거절 사유 코드 필수
6. `CONTRACTED` 약정 체결 → 전자서명 또는 서면 계약서 회수
7. `DISBURSED` 실행 → 송금 완료 확인 후 여신관리로 이관

구현은 enum + 전이 허용표(`Map<Status, Set<Status>>`)로 충분하며, 복잡해지면 Spring Statemachine을 사용합니다. 모든 전이는 `ApplicationStatusHistory`에 이전·이후 상태, 처리자, 사유, 시각을 남깁니다.

### 2.3 심사 부가 요소

- **상품**: 금리 범위, 한도, 기간, 허용 상환방식, 담보 유무, 중도상환수수료 정책을 상품에 묶고, 상품 변경은 버전으로 관리(계약은 실행 당시 버전을 참조)
- **심사자**: 보유 건수, 담당 상품, 부재 여부를 갖는 `Underwriter` 프로필, 배정은 동시성 제어(비관적 락 또는 Redis 락)
- **중개업자**: 등록 번호, 수수료율(기간별 이력), 불완전 판매 시 수수료 환수 규칙
- **적합성·설명 의무**: 금소법상 적합성 확인 결과와 설명서 교부 이력을 신청 단위로 보관

### 2.4 여신관리로 이관

실행은 심사 모듈이 `LoanDisbursedEvent`를 발행하고, 여신관리 모듈이 이를 받아 계약을 만드는 방식으로 구현합니다.

1. 실행 트랜잭션 안에서 이벤트를 Outbox 테이블에 저장 (이벤트 유실 방지)
2. 여신관리 모듈이 계약·고객·담보·보증인 스냅샷을 복사하고 상환 스케줄 생성
3. 최초 원장 거래(대출 실행)를 기록
4. 파일(신분증·소득 서류 이미지, 상담 녹취)은 물리 복사 대신 `StoredFile` 참조를 계약에 연결 (저장소는 S3 호환 오브젝트 스토리지 또는 NAS)
5. 이관은 멱등(idempotent)해야 하므로, 신청 ID에 유니크 제약을 두고 재처리 시 건너뜀

심사와 여신관리를 분리하는 이유는 두 도메인의 변경 주기와 담당 부서가 다르기 때문입니다. 실행 이후 심사 데이터는 읽기 전용으로 둡니다.

## 3. 상환방식과 스케줄 계산

상환방식은 전략 패턴(`RepaymentMethod` 인터페이스)으로 두고, 스케줄은 “예정”일 뿐이므로 실제 입금 충당은 항상 일할 이자 계산(4장)으로 합니다. 아래 계산식은 금융권 공통 공식입니다.

### 3.1 상환방식 비교

| 방식 | 매회 납입액 | 원금 상환 | 주로 쓰는 곳 |
| --- | --- | --- | --- |
| 원리금균등 | 일정 | 초기에 적고 후기에 많음 | 개인 신용대출, 자동차 담보 |
| 원금균등 | 점점 줄어듦 | 매회 동일 | 총이자를 줄이려는 상품 |
| 만기일시 | 이자만 | 만기에 전액 | 부동산 담보, 단기 대출 |
| 거치 후 분할 | 거치기간 이자만, 이후 원리금균등 | 거치 종료 후 | 사업자 대출 |
| 자유상환(수시) | 매월 이자 + 원금 수시 (예시대부 P5) | 차주 임의 | 한도대출 |

### 3.2 계산식

월 이율 r = 연 이율 ÷ 12, 대출원금 P, 회차 수 n일 때 원리금균등의 매회 납입액 A는 다음과 같습니다.

```latex
A = P \cdot \frac{r(1+r)^n}{(1+r)^n - 1}
```

공식상 k회차 이자는 직전 잔액 × r이지만, 예시대부 정책은 회차 이자를 실제 일수로 일할 계산하고 원금 = A − 일할 이자로 둡니다(정책서 2장). 원금균등의 k회차 납입액은 다음과 같습니다.

```latex
A_k = \frac{P}{n} + \left(P - \frac{P}{n}(k-1)\right) \cdot r
```

### 3.3 스케줄 생성 규칙

- **약정일(납입일)**: 계약마다 매월 납입일을 두고(일반적으로 1\~31, 예시대부는 5·10·15·20·25일만), 해당 월에 그 날이 없으면 말일로 보정 (`YearMonth.atEndOfMonth()`)
- **첨 회차**: 실행일에서 첫 납입일까지가 한 달과 다르면 첫 회차 이자는 실제 일수로 일할 계산
- **휴일**: 납입일이 휴일이면 다음 영업일까지 납입해도 연체로 보지 않는 유예 규칙을 설정값으로 둠
- **단수 처리**: 회차별 금액은 원 미만 절사하고, 누적 오차는 마지막 회차 원금에서 정산
- **재산출**: 금리 변경, 일부 중도상환, 약정일 변경, 채무조정 시 잔여 회차를 다시 생성하고, 이전 스케줄은 버전으로 보관 (삭제 금지)

### 3.4 구현 스케치

```java
public sealed interface RepaymentMethod
		permits EqualInstallment, EqualPrincipal, Bullet, GraceThenInstallment, Revolving {
	List<ScheduledInstallment> buildSchedule(ScheduleRequest req);
}

public record ScheduleRequest(
		Money principal, InterestRate annualRate, LocalDate disbursedOn,
		int termMonths, int dueDayOfMonth, int graceMonths) {}

public record ScheduledInstallment(
		int seq, LocalDate dueDate, Money principal, Money interest, Money balanceAfter) {}
```

상환방식별 구현은 순수 함수로 작성하고, 엑셀로 검증한 기대값을 `@ParameterizedTest`(CSV 소스)로 대조합니다. 포트폴리오에서 가장 보여주기 좋은 부분입니다.

## 4. 이자 계산 규칙

이자는 “금리·잔액이 일정한 구간”으로 기간을 잘라 구간별 일할 이자를 합산하는 방식으로 구현합니다. 이 구조 하나로 정상이자, 연체이자, 소급 계산을 모두 처리할 수 있습니다.

### 4.1 일할 이자

```latex
I = \sum_{i} B_i \cdot \frac{R_i}{100} \cdot \frac{d_i}{D}
```

Bᵢ는 구간 i의 잔액, Rᵢ는 연이율(%), dᵢ는 구간 일수, D는 연 일수(365 고정 또는 윤년 366)입니다. D의 기준은 약관에 따라 다르며, 예시대부는 365 고정입니다.

### 4.2 기간 계산 (초일산입)

| 방식 | 일수 계산 | 비고 |
| --- | --- | --- |
| 한편넣기 (초일산입·말일불산입) | 실행일 포함, 상환일 제외 | 금융권 대출의 일반적 관행 |
| 초일불산입 | 실행일 제외, 상환일 포함 | 민법 제157조의 기간 계산 원칙 |
| 양편넣기 | 양쪽 모두 포함 | 당일 실행·당일 상환 시 1일 이자 부과 등 |

반개구간 `[from, to)` 하나로 표현하고, 방식에 따라 from/to를 하루씩 보정하는 `DayCountConvention` enum을 두면 계산 로직을 하나로 유지할 수 있습니다.

### 4.3 법정 상한과 연체이자

- **최고이자율**: 대부업법 시행령상 연 20%. 수수료·선취이자 등 명칭에 관계없이 대부와 관련해 받는 금액은 이자로 본다(간주이자). 초과분은 무효
- **연체이자율**: 금융위원회 기준으로 “약정이자율 + 3%p 이내”, 이 경우에도 최고이자율을 넘지 못함 (적용 기준일은 법령 원문 확인 필요)
- **연체이자 부과 대상**: 기한이익 상실 전에는 납입일이 지난 미납 분할상환금에만, 상실 후에는 대출잔액 전체에 부과

구현에서는 적용 금리를 `min(약정이율 + 가산, 법정상한)`으로 계산하는 `RateCapPolicy`를 두고, 상한값은 적용 시작일이 있는 설정 테이블로 관리합니다(법 개정 대응).

### 4.4 중도상환수수료

- 금소법상 원칙적으로 금지되며, 대출일로부터 3년 이내 상환하는 경우에만 예외적으로 부과 가능
- 2025년 1월 13일부터는 자금운용 기회비용, 행정·모집비용 등 실비용 범위 안에서만 부과
- 대부업자에 같은 규칙이 그대로 적용되는지는 법령 원문 확인 필요

일반적인 잔존기간 비례 계산식은 다음과 같습니다.

```latex
Fee = \text{상환원금} \times \text{수수료율} \times \frac{\text{잔존일수}}{\text{면제기준일수}}
```

상품에 `PrepaymentFeePolicy`(적용 여부, 요율, 면제 기간, 면제 사유)를 두고, 수수료를 최고이자율 산정에 넣을지는 법률 검토 사항입니다(예시대부는 R10: 제외).

### 4.5 충당 순서

입금액은 민법 제479조의 충당 순서(비용 → 이자 → 원본)를 기본으로 합니다. 실무에서는 비용 → 연체이자 → 정상이자 → 원금 순으로 세분하며, 순서는 `AllocationPolicy`로 교체 가능하게 둡니다. 모든 입금은 항목별 충당 결과를 원장에 남겨야 나중에 취소·재처리할 수 있습니다.

출처: [대부업법 시행령 제5조](https://korea.legal/wiki/%eb%8c%80%eb%b6%80%ec%97%85-%eb%93%b1%ec%9d%98-%eb%93%b1%eb%a1%9d-%eb%b0%8f-%ea%b8%88%ec%9c%b5%ec%9d%b4%ec%9a%a9%ec%9e%90-%eb%b3%b4%ed%98%b8%ec%97%90-%ea%b4%80%ed%95%9c-%eb%b2%95-11/) · [금융위원회 중도상환수수료 안내](https://www.fsc.go.kr/edu/news/83839)

## 5. 이력(유효기간) 모델링과 소급 입금 처리

금리·납입일처럼 시점에 따라 달라지는 값은 “현재값 컬럼”이 아니라 유효기간이 있는 이력 엔티티로 저장해야, 과거 날짜 기준으로 이자를 다시 계산할 수 있습니다. 이것이 소급 입금을 정확하게 처리하는 전제 조건입니다.

### 5.1 이력 엔티티 설계

| 엔티티 | 주요 필드 | 용도 |
| --- | --- | --- |
| `RateTerm` | 계약ID, 연이율, validFrom, validTo, 변경사유, 등록시각 | 특정 날짜의 적용 금리 |
| `DueDayTerm` | 계약ID, 납입일, validFrom, validTo, 변경사유, 등록시각 | 특정 날짜의 약정일 |
| `TermChange` (선택) | 변경 요청·승인 정보 | 조건 변경 결재 추적 |

- **유효시간(valid time)**: 그 값이 실제로 적용되는 기간 `[validFrom, validTo)`. 현재 값은 `validTo = null`
- **기록시간(transaction time)**: 시스템에 등록된 시각. “그때 시스템은 무엇을 알고 있었나”를 재현할 때 필요 (이 두 축을 함께 두는 것을 bitemporal 모델이라 합니다)
- **겹침 방지**: PostgreSQL에서는 `daterange` 컬럼과 배타 제약으로 DB 레벨에서 막을 수 있습니다.

```sql
ALTER TABLE rate_term ADD CONSTRAINT rate_term_no_overlap
	EXCLUDE USING gist (account_id WITH =, daterange(valid_from, valid_to) WITH &&);
```

- **조회**: “날짜 d의 금리”는 `validFrom <= d AND (validTo IS NULL OR d < validTo)`. 여러 날짜를 계산할 때는 기간 전체 이력을 한 번에 읽어 메모리에서 구간을 만듭니다 (N+1 방지)

### 5.2 구간 분할 (Segment)

이자 계산 기간 `[from, to)` 안에서 금리 이력의 변경일, 잔액이 바뀐 거래의 기산일, 미납 원금이 바뀐 날(가산이자 항목만)을 모아 정렬하면 경계점 목록이 됩니다. 미납된 약정일 자체는 경계가 아닙니다(정책서 R12). 인접한 경계점 사이가 하나의 구간이고, 구간 안에서는 잔액·금리가 일정하므로 4.1의 식을 그대로 적용합니다.

```java
record InterestSegment(DateRange period, Money balance, InterestRate rate, SegmentKind kind) {}

List<InterestSegment> segments = SegmentBuilder.of(account)
		.rateTerms(rateTermRepo.findOverlapping(accountId, period))
		.balanceEvents(ledger.balanceChangesIn(period))
		.delinquencyStart(delinquency.startDate())
		.build(period);
```

### 5.3 소급 입금 처리

소급 입금은 “실제 입금일(기산일)이 처리일보다 과거인 거래”입니다. 예를 들어 미확인 입금을 열흘 뒤에 주인을 찾아 처리하면, 그 사이의 이자·연체 여부가 모두 바뀝니다.

1. 계약 단위 락 획득 (`SELECT ... FOR UPDATE` 또는 Redis 락)
2. 기산일 이후의 모든 거래를 기산일 순으로 조회
3. 이 거래들의 충당 결과를 취소 처리 (삭제하지 않고 `superseded` 표시)
4. 기산일 직전 시점의 잔액·미수이자를 복원
5. 새 입금을 끼워 넣고, 이후 거래를 기산일 순으로 다시 충당 (각 시점의 금리·약정일은 이력에서 조회)
6. 재계산 결과로 연체 일수, 다음 납입일, 기한이익상실 여부를 갱신하고 변경 전후 차이를 감사 로그에 기록

마감된 날짜의 일보·대사 결과는 바꾸지 않고, 재계산은 원 기산일부터 하되 차액을 처리일 일보의 ‘정정’으로 표시합니다(정책서 R28). 이 재생(replay) 로직은 결정적이어야 하므로, 동일 입력 → 동일 결과를 보장하는 테스트를 반드시 작성합니다.

## 6. 거래원장·입출금·미확인 입금·비용 처리

원장은 수정·삭제가 없는 추가 전용(append-only) 구조로 두고, 모든 정정은 반대 거래(역분개)로 합니다. 계약의 현재 잔액은 원장에서 계산할 수 있어야 하며, 성능을 위한 요약 컬럼은 일마감에서 검증합니다.

### 6.1 거래원장 (Ledger)

| 필드 | 내용 |
| --- | --- |
| 거래유형 | 실행, 상환, 수수료, 비용지급, 비용회수, 조정, 역분개, 상각, 매각, 환매, 채무조정 |
| 기산일 / 처리일시 | 이자 계산 기준일과 실제 등록 시각을 분리 (5장 소급의 근거) |
| 항목별 금액 | 원금, 정상이자, 연체이자, 비용, 수수료, 가수금 |
| 거래 후 잔액 | 원금 잔액, 미수이자, 미회수 비용 |
| 원거래 참조 | 역분개·재처리 거래가 가리키는 원래 거래 |
| 자금 출처 | 가상계좌, 계좌이체, 자동이체, 현금, 미확인 입금 전환 등 |

회계 연동이 필요하면 원장 거래마다 복식부기 분개(차변·대변)를 자동 생성하는 `JournalMapper`를 두고, 계정과목 매핑은 설정 테이블로 둡니다.

### 6.2 입금 채널과 매칭

| 채널 | 매칭 방식 | 구현 포인트 |
| --- | --- | --- |
| 가상계좌 | 계약별 고유 가상계좌번호 → 자동 매칭 | 입금 통지 수신 후 즉시 충당, 중복 통지는 거래고유번호로 무시 |
| 회사 계좌 이체 | 입금자명·금액으로 후보 추천 | 자동 확정하지 않고 담당자 확인 |
| 자동이체(CMS) | 출금 요청 → 결과 수신 | 요청·결과 상태 추적, 실패 사유 코드 보관 |
| 현장 수납 | 수기 등록 | 영수증 번호, 승인 필요 |

### 6.3 미확인 입금

입금자를 특정하지 못한 금액은 `UnmatchedDeposit`으로 따로 보관합니다.

1. `OPEN`: 입금일, 금액, 입금자명, 입금 계좌를 기록
2. `MATCHED`: 담당자가 계약을 지정하면 기산일 = 원래 입금일로 소급 입금 처리 (5.3)
3. `SPLIT`: 한 금액을 여러 계약에 나눠 충당
4. `REFUNDED`: 반환 출금 후 종료

미확인 입금 잔액은 부채(가수금)이므로 일마감 시 장부 잔액과 대사합니다.

### 6.4 출금

- 대상: 대출 실행금 송금, 과입금·미확인 입금 반환, 비용 지급
- 작성자·승인자 분리(maker-checker)를 강제하고, 동일 요청 재전송을 막기 위해 멱등 키(idempotency key)를 포함
- 송금 결과가 불확실(타임아웃)하면 성공으로 가정하지 않고 결과 조회 배치로 확정
- 예금주 실명 확인 후 송금 (연계는 11장의 어댑터 인터페이스로 추상화)

### 6.5 비용(법조치 비용 등) 처리

1. 지급: 인지대·송달료 등을 회사가 선지급하면 계약에 “미회수 비용”으로 적재
2. 회수: 입금 시 충당 순서(4.5)에 따라 비용부터 회수
3. 포기: 회수 불능이 확정되면 손실 처리 거래를 남김

비용도 간주이자 판단 대상이 될 수 있으므로, 법령상 채무자 부담이 허용되는 비용만 청구 대상으로 분류합니다.

### 6.6 일마감

- 계약별 요약 잔액 = 원장 합계 검증, 불일치는 알림
- 은행 계좌 잔액 대사 (입금 합계, 미확인 입금 합계)
- 마감 후에는 해당 일자의 거래 생성·취소를 막고(예외: 정책서 R28 마감 후 취소), 필요하면 권한자가 마감 취소

## 7. 채권 생애주기

계약은 “상환 상태”와 “관리 구분” 두 축으로 관리하면 조합 폭발을 막을 수 있습니다. 상환 상태는 정상·연체·기한이익상실·종결처럼 하나만 갖고, 법조치·채무조정 같은 관리 구분은 별도 엔티티로 겹쳐 붙습니다.

&#91;embedded content: 채권 생애주기 · 상태 10개, 연체 해소와 환매는 되돌아가는 전이\]

### 7.1 주요 상태와 전이 규칙

| 상태 | 진입 조건 | 시스템 처리 |
| --- | --- | --- |
| 정상 | 실행 또는 연체 해소 | 다음 납입일 계산 |
| 연체 | 납입일(휴일 유예 후) 다음 날까지 미납 | 연체 기산일 기록, 연체이자 구간 시작, 연체 일수 매일 계산 |
| 기한이익상실 | 약관상 사유 충족 + 사전 통지 후 통지 기간 경과 | 잔액 전체 변제기 도래, 스케줄 중단, 신용정보 등록 대상 검토 |
| 채무조정(화해) | 채무자와 합의 | 감면액·분할 조건으로 새 스케줄, 미이행 시 원래 조건 부활 여부 설정 |
| 상각 | 회수 가능성 평가에 따른 내부 결재 | 장부상 자산 제거, 채권은 존속하므로 상각 후 회수 가능 |
| 매각 | 채권 양도 계약 체결 | 양도 통지, 매각가·양수인 기록, 관리 종료 |
| 환매 | 매각 채권의 되사기 (하자 등 계약 조건) | 매각 이전 상태로 복원하거나 새 취득 건으로 등록 |
| 완제 | 원금·이자·비용 전액 회수 | 완제증명서 발급, 가상계좌 해지, 담보 해지, 보관기간 후 파기 예약 |

매입 채권(다른 금융사에서 양수한 채권)은 실행 대신 “취득”으로 시작하며, 취득가·양도인·양수 시점의 잔액을 기록하는 점만 다릅니다.

### 7.2 기한이익상실

은행권 여신거래기본약관의 일반적인 사유는 다음과 같으며, 수치는 상품 설정값으로 둡니다.

- 이자를 납입할 때부터 1개월(주택담보 2개월) 지체
- 분할상환금을 2회(주택담보 3회) 이상 연속 지체
- 상실 예정일 전에 채무자에게 통지하고, 통지 도달 후 일정 영업일(약관·금액별로 7\~10영업일 등)이 지나야 상실

구현은 매일 배치가 조건 충족 건을 찾아 “상실 예정”으로 표시하고, 통지 발송·도달 이력을 확인한 뒤 예정일에 상실 처리하는 2단계로 둡니다. 상실일은 반드시 이력으로 남겨야 소급 입금 시 상실 취소(부활) 여부를 판단할 수 있습니다.

### 7.3 추심 활동과 개인채무자보호법

2024년 10월 17일 시행된 「개인금융채권의 관리 및 개인금융채무자의 보호에 관한 법률」은 회수 시스템의 필수 기능을 정합니다.

| 규정 | 시스템 기능 |
| --- | --- |
| 추심 연락은 채권 1건당 7일에 7회까지 (법령상 의무 통지는 제외) | 전화·문자·방문 등 연락 이력을 집계해 초과 시 차단하는 `ContactQuotaGuard` |
| 재난·입원 등 사유 시 추심 연락 유예 | 계약별 연락 유예 기간 등록, 유예 중 발송 차단 |
| 채무자의 채무조정 요청권 | 요청 접수·검토·회신 기한 관리 워크플로 |
| 기한이익상실 후 연체이자 부과 범위 제한 (일정 금액 미만) | 금액 기준에 따라 연체이자 구간 계산을 바꾸는 정책 (금액 기준은 원문 확인) |

### 7.4 법조치와 소멸시효

- **법조치 종류**: 지급명령, 소송, 가압류, 강제집행(채권압류·추심명령, 경매), 재산명시. `LegalAction`에 사건번호, 법원, 진행 단계, 기일, 비용, 결과를 기록
- **소멸시효 기간**: 상사채권 5년(상법 제64조), 판결 등으로 확정된 채권 10년(민법 제165조)
- **중단(갱신) 사유**: 청구, 압류·가압류·가처분, 채무 승인(민법 제168조). 채무자의 일부 변제도 승인으로 본다
- **구현**: `LimitationPeriod`가 기산점과 기간, 마지막 중단 사유를 보관하고, 입금·법조치 이벤트를 받을 때마다 완성 예정일을 재계산. 완성 6개월 전 등 임박 건을 담당자에게 알림
- 소멸시효가 완성된 채권에 대한 추심은 제한되므로, 완성 건은 추심 연락 기능을 막습니다

출처: [여신거래기본약관 예시(하나은행)](https://image.kebhana.com/cont/download/documents/provide/0000005160131_20240101.pdf) · [금융위원회 개인채무자보호법 안내](https://fsc.go.kr/po010102/83207)

## 8. 변경 이력·감사 로그 설계

여신 시스템에서는 “누가, 언제, 무엇을, 왜 바꿨는가”를 언제든 보여줄 수 있어야 합니다. 목적이 다른 세 가지 이력을 나눠 설계합니다.

| 종류 | 대상 | 구현 |
| --- | --- | --- |
| 업무 이력 | 금리·납입일·담당자·상태 변경 | 도메인 이력 엔티티 (5장), 변경 사유 필수 |
| 데이터 변경 이력 | 고객 정보(주소·연락처·직장 등), 계약 매니패킹 정보 | Hibernate Envers(`@Audited`) 또는 변경 전후 값 JSON 저장 |
| 개인정보 처리 기록 | 개인정보 조회·다운로드·수정, 로그인 | AOP 기반 `@AccessLog` + 별도 테이블, 수정 불가 |

### 8.1 고객 정보 변경 이력

- 변경 전·후 값, 변경 항목, 변경자, 출처(고객 요청, 신용정보 갱신, 직원 수정)를 기록
- 암호화 대상 필드는 이력에도 암호문으로 저장 (이력 테이블이 우회 경로가 되지 않게)
- 연락처는 덮어쓰지 않고 여러 건을 유효·무효 상태로 관리하면 추심 연락 가능 번호 판단이 쉬움

### 8.2 접근 기록

개인정보 보호법의 안전성 확보조치 기준은 개인정보처리시스템의 접속기록을 보관·점검하도록 요구합니다. 기록 항목은 계정, 접속일시, 접속지(IP), 처리한 정보주체, 수행 업무(조회·수정·다운로드)로 두고, 보관 기간은 고시 원문을 확인해 설정합니다. 대량 조회·다운로드는 사유 입력을 받고, 임계치 초과 시 관리자에게 알립니다.

## 9. 직원·권한 관리

권한은 “역할(Role) 기본 + 직원별 예외(추가·회수)” 구조로 설계하고, 메뉴(화면 접근)와 기능(버튼·API)을 같은 권한 코드 체계로 통일합니다.

### 9.1 도메인 모델

| 엔티티 | 내용 |
| --- | --- |
| `Staff` | 사번, 이름, 소속 조직, 직급, 재직 상태, 접속 허용 IP, 비밀번호 변경일, 잠금 상태 |
| `Organization` | 부서·팀 트리 (상위 조직 참조) |
| `Role` | 심사자, 채권담당, 법무, 출납, 관리자 등 역할과 보유 권한 목록 |
| `Permission` | `loan.read`, `loan.rate.update`, `payment.refund.approve`, `customer.export` 처럼 리소스.동작 형식 |
| `StaffPermissionOverride` | 직원별 추가·회수 권한, 유효기간(대직 등 임시 권한) |
| `Menu` | 메뉴 트리, URL, 필요 권한 |
| `ApprovalRule` | 금액·업무별 결재권자 (예: 일정 금액 이상 감면은 팀장 승인) |

### 9.2 Spring Security 적용

1. 로그인 시 역할 권한 + 직원별 추가 − 회수 권한을 합쳐 `GrantedAuthority` 목록 생성 (Redis에 캐시, 권한 변경 시 무효화)
2. URL 접근은 `AuthorizationManager`가 `Menu` 테이블의 필요 권한을 조회해 판단
3. 서비스 메서드는 `@PreAuthorize("hasAuthority('loan.rate.update')")`로 이중 방어
4. 화면은 Thymeleaf `sec:authorize`로 버튼 노출 제어 (화면 숨김은 편의 기능일 뿐, 실제 통제는 서버)
5. 데이터 범위 권한(내 담당 계약만, 우리 팀 계약만)은 검색 쿼리에 조건을 주입하는 `DataScopeResolver`로 처리

### 9.3 계정 보안 정책

- 비밀번호는 BCrypt 또는 Argon2로 일방향 암호화 (`DelegatingPasswordEncoder`)
- 로그인 실패 횟수 초과 시 잠금, 비밀번호 변경 주기 경과 시 변경 강제, 초기 비밀번호는 첫 로그인 시 변경 강제 (값은 설정으로)
- 직원별 세션 타임아웃, 동시 로그인 제한(`maximumSessions`), 접속 허용 IP
- 권한 부여·변경·말소 이력은 별도 보관 (개인정보 안전성 확보조치 기준의 접근 권한 관리 요구)
- 퇴사·전보 시 권한 즉시 회수, 담당 계약 재배정 화면 제공

## 10. 개인정보 암호화 일반 설계

암호화 대상은 법령이 정하고, 방식은 공개 표준(AES-GCM, HMAC, BCrypt/Argon2)을 따릅니다. 아래는 어느 회사의 구성도 아닌 교과서적 설계입니다.

### 10.1 법적 요구사항 (개인정보의 안전성 확보조치 기준 제7조)

| 대상 | 요구 | 구현 |
| --- | --- | --- |
| 비밀번호 | 복호화되지 않도록 일방향 암호화 | BCrypt 또는 Argon2 (솔트 포함) |
| 고유식별정보 (주민등록번호, 여권·운전면허·외국인등록번호) | 안전한 알고리즘으로 암호화 저장 (주민등록번호는 항상) | AES-256-GCM 양방향 암호화 |
| 전송 구간 | 인증정보·개인정보 송수신 시 암호화 | TLS 1.2 이상, 기관 연계도 TLS 또는 전문 암호화 |
| 단말·보조저장매체 | 고유식별정보 저장 시 암호화 | 엑셀 다운로드 시 마스킹 또는 파일 암호 |
| 암호 키 | 생성·보관·배포·파기 절차 수립 (일정 규모 이상) | KMS/Vault, 키 버전 관리 |

금융 거래 정보는 신용정보법의 보호 의무도 함께 적용되므로, 계좌번호·소득 정보 등은 위험도에 따라 암호화 범위를 정합니다.

### 10.2 컬럼 암호화 구현

```java
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {
	private final FieldCipher cipher; // AES-256-GCM, 랜덤 IV 12바이트

	@Override
	public String convertToDatabaseColumn(String plain) {
		return plain == null ? null : cipher.encrypt(plain); // "v1:" + base64(iv || ciphertext || tag)
	}

	@Override
	public String convertToEntityAttribute(String stored) {
		return stored == null ? null : cipher.decrypt(stored);
	}
}
```

- 저장값 접두어에 키 버전(`v1:`)을 붙여 키 교체 시 구·신 키를 함께 읽고, 배치로 점진 재암호화
- 키는 코드·설정 파일에 두지 않고 KMS(AWS KMS, HashiCorp Vault)에서 데이터 키를 받아 메모리에만 보관 (봉투 암호화)
- 암호화 대상 필드는 엔티티에 `@Convert(converter = EncryptedStringConverter.class)`로만 선언해 누락을 막고, ArchUnit으로 규칙 검사

### 10.3 암호화 필드 검색 (블라인드 인덱스)

GCM은 같은 평문도 매번 다른 암호문을 만들므로 `WHERE` 검색이 불가능합니다. 검색이 필요한 필드는 별도 컬럼에 `HMAC-SHA256(검색키, 정규화된 평문)`을 저장하고 인덱스를 겁니다. 검색키는 암호화키와 분리합니다. 중복 신청 검사(2장), 고객 검색이 이 방식을 씁니다.

### 10.4 마스킹과 파기

- 화면·엑셀·로그는 기본 마스킹(예: 주민번호 뒷자리, 전화번호 가운데), 원문 조회는 권한 + 사유 입력 + 접근 기록
- 로그에 개인정보가 찍히지 않도록 Logback 마스킹 패턴 적용, DTO의 `toString()`에서 제외
- 거래 종료 고객의 정보는 법정 보관기간 동안 분리 보관한 뒤 파기하는 배치 (기간은 신용정보법·상법 등 원문 확인)

출처: [개인정보의 안전성 확보조치 기준 해설(wikidocs)](https://wikidocs.net/264634) · [제7조 요약(itwiki)](https://itwiki.kr/w/%EA%B0%9C%EC%9D%B8%EC%A0%95%EB%B3%B4%EC%9D%98_%EC%95%88%EC%A0%84%EC%84%B1_%ED%99%95%EB%B3%B4%EC%A1%B0%EC%B9%98_%EA%B8%B0%EC%A4%80_%EC%A0%9C7%EC%A1%B0)

## 11. 대외 연계 어댑터 설계

외부 기관은 포트(인터페이스)와 어댑터(구현)로 분리하고, 포트폴리오에서는 Mock 어댑터만 구현합니다. 실제 기관 전문은 기관과 계약해야 받을 수 있는 비공개 정보이므로 문서에 담지 않습니다.

### 11.1 포트 목록

| 포트 | 역할 | 호출 방식 | Mock 동작 |
| --- | --- | --- | --- |
| `CreditBureauPort` | 신용조회(등급·대출·연체 정보) | 동기 요청/응답 | 식별값 끝자리로 등급·연체 여부 결정 |
| `CreditReportingPort` | 신용정보 등록·변경·해제 보고(정보집중) | 일별 파일 생성 → 전송 → 결과 파일 수신 | 전송 파일을 로컬에 저장, 오류 결과 파일 임의 생성 |
| `IdentityVerificationPort` | 본인인증(휴대폰·인증서) | 팝업/리다이렉트 후 콜백 | 가짜 인증 화면에서 성공·실패 선택 |
| `AccountVerificationPort` | 예금주 실명 확인, 1원 인증 | 동기 | 예금주명 일치 여부 반환 |
| `TransferPort` | 송금(펌뱅킹) | 요청 → 결과 조회 | 지연·타임아웃·실패 시나리오 주입 |
| `VirtualAccountPort` | 가상계좌 발급·해지, 입금 통지 수신 | 발급은 동기, 입금은 웹훅 | 관리자 화면에서 가짜 입금 발생 |
| `AutoDebitPort` | 자동이체 등록·출금 요청·결과 | 파일 배치 | 요청 파일 기반 결과 파일 생성 |
| `MessagingPort` | 문자·알림톡, 수신(MO) | 비동기 + 결과 콜백 | 콘솔 출력, 발송 이력 저장 |
| `FaxPort` / `PostalPort` | 팩스, 등기우편 발송·배달 조회 | 비동기 | PDF 파일 저장, 배달 상태 임의 변경 |
| `PublicRecordPort` | 등기부등본, 자동차등록원부, 공공 서류 조회 | 비동기(스크래핑·중계 서비스) | 샘플 PDF 반환 |
| `CollateralValuationPort` | 차량·부동산 시세 | 동기 | 고정 시세표 |
| `LoanPlatformPort` | 대출 비교 플랫폼 한도조회·신청·결과 | 수신 API + 비동기 회신 | 가짜 플랫폼 클라이언트 |

### 11.2 공통 구현 규칙

- **요청 이력**: 모든 연계는 `IntegrationLog`(기관, 업무, 요청·응답 요약, 소요시간, 결과코드)에 기록. 개인정보는 마스킹
- **재시도·차단**: Resilience4j로 타임아웃, 재시도(멱등한 조회만), 서킷브레이커
- **비동기 콜백**: 서명·IP 검증, 중복 콜백은 기관 거래번호로 무시, 최초 수신 시 즉시 200 응답 후 내부 처리는 이벤트로
- **파일 연계**: 고정길이 전문은 레이아웃을 어노테이션으로 선언하는 자체 `FixedLengthRecord` 매퍼로 처리 (바이트 기준 길이, EUC-KR 등 문자셋 주의), 전송은 SFTP
- **환경 전환**: `@Profile("mock")` / `@Profile("prod")` 또는 `@ConditionalOnProperty`로 어댑터 교체
- **동의 확인**: 신용조회·제3자 제공 전에 유효한 동의 이력이 있는지 포트 호출 전 검사 (신용정보법)

### 11.3 본인인증 기반 랜딩 페이지 흐름 (일반형)

1. 고객이 문자 링크 또는 홈페이지로 랜딩 페이지 진입 (링크에는 일회용 토큰, 유효기간 짧게)
2. 본인인증 팝업 → 인증 기관 콜백으로 인증 결과 수신 (연계정보 CI 등)
3. 서버가 인증 결과의 식별값과 토큰에 묶인 고객을 대조해 일치할 때만 다음 단계 허용
4. 동의·서류 제출·약정 등 업무 수행, 결과를 사내 화면에 알림

## 12. 배치·스케줄·비동기 처리

스케줄은 DB 테이블로 관리해 운영 중에 화면에서 켜고 끌 수 있게 하고, 대량 처리는 Spring Batch, 건별 후속 처리는 이벤트로 나눕니다.

### 12.1 스케줄 관리

- `BatchSchedule` 테이블: 작업명, cron 식, 사용 여부, 파라미터, 담당자
- 기동 시 테이블을 읽어 `ThreadPoolTaskScheduler`에 등록하고, 변경 시 재등록 (또는 Quartz JDBC JobStore 사용)
- 다중 서버에서는 ShedLock으로 한 대만 실행, cron 식은 등록 전 `CronExpression.parse()`로 검증
- 실행 이력(시작·종료·건수·오류)은 Spring Batch 메타 테이블 + 화면 조회, 수동 재실행 버튼 제공

### 12.2 대표 배치 목록

| 주기 | 작업 | 비고 |
| --- | --- | --- |
| 매일 새벽 | 연체 판정·연체 일수 갱신 | 영업일 달력 반영 |
| 매일 새벽 | 기한이익상실 예정·확정 | 7.2의 2단계 처리 |
| 매일 새벽 | 미수이자 발생 및 요약 잔액 갱신 | 조회 성능용 |
| 매일 | 납입 안내·연체 안내 문자 | 추심 연락 한도(7.3) 검사 후 발송 |
| 매일 | 신용정보 보고 파일 생성·전송·결과 반영 | 파일 연계 |
| 매일 마감 | 일마감·잔액 대사, 보고서 집계 스냅샷 | 6.6 |
| 매월 | 대손충당금 산출용 연체 구간별 집계, 월마감 |  |
| 수시 | 엑셀 업로드 일괄 처리 (담당자 변경, 입금 일괄 등록 등) | 12.3 |
| 매월/분기 | 보관기간 경과 개인정보 분리·파기 | 10.4 |

### 12.3 엑셀 일괄 처리 패턴

1. 업로드: 파일을 저장하고 `BulkJob`(유형, 상태, 총건수) 생성
2. 검증: 행별 형식·존재 여부·권한 검사 결과를 `BulkJobRow`에 저장하고 미리보기 화면 제공
3. 실행: 승인 후 Spring Batch Job으로 비동기 실행 (chunk 단위 커밋, 행 단위 실패 기록 후 계속)
4. 결과: 진행률을 폴링(또는 SSE)으로 보여주고, 실패 행만 엑셀로 다운로드

포트폴리오에서는 이 패턴을 하나의 추상 클래스(`AbstractBulkProcessor<T>`)로 만들고 유형별 구현만 추가하는 구조를 보여주면 좋습니다.

### 12.4 비동기 처리

- 도메인 이벤트는 `@TransactionalEventListener(phase = AFTER_COMMIT)`로 커밋 후 처리
- 유실되면 안 되는 이벤트(실행 → 계약 생성, 신용정보 보고 대상 변경)는 Transactional Outbox + 폴링 발행기
- OCR, 문서 생성, 대용량 엑셀 다운로드처럼 오래 걸리는 작업은 작업 큐(RabbitMQ 또는 DB 큐) + 워커, 결과는 알림으로 통보
- 계약 단위 작업은 동시에 두 건이 실행되지 않도록 계약 ID 기준 락 또는 파티셔닝

## 13. 화면 구성

업무 화면 대부분은 “검색 조건 + 상태별 탭 + 목록 + 상세 팝업” 형태이므로, 이를 공통 컴포넌트 하나로 만들고 화면별로는 설정만 작성하는 구조를 권장합니다. 아래는 Bootstrap 5 + 바닐라 JS 기준의 자체 설계입니다.

### 13.1 목록 화면 공통 컴포넌트

| 구성 요소 | 서버 | 화면 |
| --- | --- | --- |
| 검색 조건 | `SearchCondition` DTO → QueryDSL `BooleanBuilder` | Thymeleaf fragment로 입력 요소 생성 |
| 상태 탭 + 건수 | 탭별 건수를 한 번에 집계하는 `GROUP BY` 쿼리 | 탭 클릭 시 조건 추가 후 재조회 |
| 목록 | `Page<T>` JSON (정렬·페이징 파라미터 표준화) | 컬럼 정의 배열로 테이블 렌더링 |
| 엑셀 | 같은 검색 조건으로 스트리밍 다운로드 (SXSSF) | 다운로드 사유 입력 모달 (8.2 접근 기록) |
| 상세 | 행 클릭 → 상세 화면 또는 모달 | 새 창/탭 모두 지원 |

```javascript
const page = new ListPage({
	endpoint: '/api/loans',
	form: '#search-form',
	tabs: { field: 'status', countsEndpoint: '/api/loans/status-counts' },
	columns: [
		{ key: 'accountNo', label: '계약번호', link: row => `/loans/${row.id}` },
		{ key: 'customerName', label: '고객명' },
		{ key: 'balance', label: '잔액', format: 'money', align: 'end' },
		{ key: 'overdueDays', label: '연체일수', format: 'number' },
	],
	pageSize: 50,
});
```

검색 조건은 URL 쿼리스트링에 동기화해 뒤로 가기·새로고침·링크 공유 시에도 같은 목록이 보이게 합니다.

### 13.2 계약 상세 화면 (고밀도 레이아웃)

채권 담당자는 한 화면에서 계약 정보를 보면서 고객과 연락해야 하므로, 3단 구성이 효율적입니다.

| 영역 | 내용 | 구현 |
| --- | --- | --- |
| 상단 요약 바 (고정) | 고객명, 계약번호, 상태 배지, 잔액, 연체일수, 다음 납입일, 소멸시효 완성 예정일 | `position: sticky` |
| 중앙 탭 | 계약조건, 상환스케줄, 거래원장, 담보·보증인, 법조치, 조건변경 이력, 문서 | 탭별 지연 로딩(fetch 후 fragment 삽입) |
| 우측 패널 | 연락 타임라인(문자·전화·메모·우편·방문), 빠른 입력 폼 | 타임라인은 여러 출처를 날짜순으로 합친 조회 API, 무한 스크롤 |

- 우측 패널의 문자 발송은 추심 연락 한도(7.3)와 연락 유예 여부를 서버에서 검사하고, 남은 횟수를 화면에 표시
- 금액·날짜 포맷은 공통 포맷터 하나로 통일 (서버 Thymeleaf 헬퍼와 JS 포맷터가 같은 규칙)
- 같은 계약을 두 직원이 동시에 수정하면 낙관적 락(`@Version`) 충돌로 알려줌

### 13.3 화면 목록 (최소 구성)

| 업무 | 화면 |
| --- | --- |
| 심사 | 신청 목록, 신청 상세(심사 입력), 심사자 배정, 상품 관리, 중개업자 관리 |
| 여신 | 계약 목록, 계약 상세, 입금 등록, 미확인 입금, 출금 승인, 조건 변경 |
| 회수 | 연체 목록, 법조치 관리, 채무조정, 상각·매각 |
| 관리 | 직원, 권한, 코드, 영업일, 배치, 문자 템플릿, 접근 기록 |
| 보고서 | 일보, 연체 현황, 연체 전이율, 대손충당금 기초 집계 |

## 14. 인프라·배포

리눅스 서버 위에서 Docker Compose로 앱·DB·Redis·리버스 프록시를 띄우고, GitHub Actions로 빌드·배포하는 구성이 포트폴리오에 적당합니다. 운영 규모가 커지면 같은 이미지를 쿠버네티스로 옮길 수 있습니다.

### 14.1 서버 구성

| 구성 요소 | 역할 | 비고 |
| --- | --- | --- |
| Nginx | TLS 종료, 정적 파일, 리버스 프록시, 업로드 크기 제한 | 내부망 화면과 외부 API는 도메인·포트 분리 |
| app (Spring Boot) | 웹·API | 스케일아웃 가능하도록 세션은 Redis(Spring Session) |
| worker (Spring Boot, 같은 이미지) | 배치·큐 소비 | `spring.profiles.active=worker`로 역할 분리 |
| PostgreSQL | 업무 DB | 일 단위 백업 + WAL 아카이빙 |
| Redis | 세션, 캐시, 락 |  |
| 오브젝트 스토리지 (MinIO) | 이미지·녹취·문서 파일 | 서버 측 암호화, 사전 서명 URL로 다운로드 |
| 모니터링 | Actuator + Prometheus + Grafana, 로그는 Loki 또는 ELK | 연계 기관별 응답시간·실패율 대시보드 |

### 14.2 리눅스 운영 포인트

- 앱은 비루트 사용자로 실행, 컨테이너 안 파일 소유권 일치
- 환경별 설정은 `application-{profile}.yml`, 비밀값은 환경변수나 Vault로 주입 (저장소에 커밋 금지)
- 로그는 stdout JSON 출력 → 수집기, 로컬 파일을 쓸 때는 logrotate
- 헬스체크: `/actuator/health`(DB·Redis·외부 기관 TCP 점검을 커스텀 `HealthIndicator`로)
- 시간대는 `Asia/Seoul`로 고정 (JVM `-Duser.timezone`, DB, 컨테이너 모두). 이자 계산 날짜가 어긋나는 흔한 원인
- 대용량 엑셀·PDF 생성은 힙 사용량을 고려해 worker에서 실행하고 JVM 메모리 한도를 컨테이너 리미트에 맞춤

### 14.3 CI/CD

1. PR: 커밋 메시지 규칙(Conventional Commits) 검사, 빌드, 단위·통합 테스트(Testcontainers), ArchUnit
2. main 병합: 컨테이너 이미지 빌드(Jib 또는 Buildpacks) → 레지스트리 푸시, 시맨틱 버전 태그
3. 배포: 서버에서 새 이미지 pull → Flyway 마이그레이션 → 롤링 재시작 (단일 서버라면 블루·그린 컨테이너 전환)
4. 배포 후 헬스체크 실패 시 이전 태그로 자동 롤백

## 15. Laravel → Spring 기술 대응표

Laravel 경험을 Spring으로 옮길 때 개념별로 대응하는 기술입니다. 프레임워크 공식 기능 기준이며 특정 프로젝트와 무관합니다.

| 영역 | Laravel | Spring Boot | 옮길 때 주의할 점 |
| --- | --- | --- | --- |
| 라우팅 | `routes/web.php`, `Route::group` | `@RequestMapping`, `@GetMapping` | 라우트 파일 대신 컨트롤러에 선언 |
| 미들웨어 | Middleware | `Filter`, `HandlerInterceptor`, Security Filter Chain | 인증·권한은 Security로, 로깅·시간 측정은 Interceptor |
| DI | Service Container, `bind()` | `@Component`, `@Bean`, 생성자 주입 | 인터페이스 구현체가 여럿이면 `@Qualifier`나 `Map<String, T>` 주입 |
| 요청 검증 | FormRequest, `validate()` | Bean Validation(`@Valid`, `@NotNull`) | 업무 규칙 검증은 도메인 계층에서 |
| ORM | Eloquent (Active Record) | JPA/Hibernate (Data Mapper) + QueryDSL | 지연 로딩·영속성 컨텍스트·변경 감지 이해 필수, N+1은 fetch join·`@EntityGraph` |
| 쿼리 빌더 | `DB::table()` | QueryDSL, JdbcTemplate, jOOQ | 대량 조회·집계는 JdbcTemplate이 단순 |
| 캐스트 | `$casts`, CastsAttributes | `AttributeConverter` | 암호화 컨버터 (10.2) |
| 트랜잭션 | `DB::transaction()` | `@Transactional` | 프록시 기반이라 같은 클래스 내부 호출에는 적용 안 됨 |
| 마이그레이션 | `php artisan migrate` | Flyway / Liquibase | 스키마 자동 생성(`ddl-auto`)은 개발에서도 끄기 권장 |
| 커맨드 | Artisan Command | `CommandLineRunner`, Spring Shell, Batch Job | 운영 배치는 Batch Job으로 |
| 스케줄러 | `schedule()`, `withoutOverlapping`, `onOneServer` | `@Scheduled`, Quartz, ShedLock | ShedLock이 `onOneServer`에 해당 |
| 큐 | Job, `dispatch()`, `queue:work` | `@Async`, Spring AMQP, Spring Batch | 재시도·DLQ를 명시적으로 설정 |
| 이벤트 | Event, Listener | `ApplicationEventPublisher`, `@EventListener` | 커밋 후 처리는 `@TransactionalEventListener` |
| 뷰 | Blade | Thymeleaf (또는 React 분리) | `@include` → `th:replace` fragment, 레이아웃은 Thymeleaf Layout Dialect |
| 세션·인증 | `Auth`, guard | Spring Security, Spring Session | CSRF는 기본 활성화, API는 별도 체인 |
| 설정 | `.env`, `config/*.php` | `application.yml`, `@ConfigurationProperties` | 타입 있는 설정 클래스로 바인딩 |
| HTTP 클라이언트 | Guzzle | `RestClient`, WebClient | 타임아웃 기본값 없음, 반드시 설정 |
| 파일 저장 | Storage, Flysystem | Spring Resource, AWS SDK(S3 호환), Spring Integration SFTP |  |
| 엑셀 | Laravel Excel | Apache POI (SXSSF 스트리밍) | 대용량은 반드시 스트리밍 API |
| 테스트 | PHPUnit, Pest | JUnit 5, Mockito, Testcontainers, `@SpringBootTest` | 슬라이스 테스트(`@DataJpaTest`, `@WebMvcTest`) 활용 |
| 헬스체크 | laravel-health | Spring Boot Actuator | 커스텀 `HealthIndicator` |

### 15.1 사고방식 차이

- **금액 타입**: PHP는 문자열·float를 섞어 쓰기 쉽지만, Java에서는 `BigDecimal`과 명시적 `RoundingMode`를 사용하고 `equals` 대신 `compareTo`로 비교
- **날짜**: 문자열 `Ymd` 대신 `LocalDate`, 기간은 `ChronoUnit.DAYS.between`
- **배열 대신 타입**: 연관 배열로 넘기던 데이터는 `record` DTO로, 상태 문자열은 `enum`으로
- **예외**: 업무 예외 계층(`BusinessException` + 오류 코드)을 두고 `@RestControllerAdvice`에서 일괄 응답

## 16. 엔티티 관계도

도메인 용어로 새로 설계한 모델입니다. 계약이 중심에 있고, 나머지 그룹은 계약 또는 고객을 참조합니다.

&#91;embedded content: 도메인 모델 · 7개 그룹, 엔티티 35개\]

### 16.1 설계 규칙

- **식별자**: 내부 PK는 `BIGINT` 시퀀스, 외부에 노출하는 번호(계약번호 등)는 별도 유니크 컬럼
- **모듈 간 참조**: 다른 모듈 엔티티는 `@ManyToOne` 대신 ID 값으로만 참조 (모듈 경계 유지)
- **스냅샷**: 계약은 실행 시점의 상품 버전·고객 주요 정보를 복사해 보관 (이후 변경에 영향받지 않게)
- **이력**: 금리·약정일은 유효기간 이력(5장), 상태는 상태 이력, 원장은 추가 전용
- **공통 컬럼**: 생성·수정 일시와 작업자(`@CreatedBy` 등 JPA Auditing), 낙관적 락 `@Version`
- **삭제**: 업무 데이터는 물리 삭제하지 않고 상태로 종료. 개인정보 파기만 별도 배치로 처리 (10.4)

### 16.2 MVP-1 엔티티 필드

위 그림은 Markdown으로 내보내면 빠지므로, 스키마 설계에 필요한 필드를 표로 둡니다. 금액은 `BIGINT`(원), 이율은 `NUMERIC(5,2)`, 날짜는 `DATE`, 시각은 `TIMESTAMPTZ`. 모든 테이블에 `id`, `created_at`, `created_by`, `updated_at`, `version`(낙관적 락)을 둡니다.

| 엔티티 | 주요 필드 |
| --- | --- |
| `LoanProduct` | code(P1\~P5, N1), name, repaymentMethod, defaultRate, minAmount, maxAmount, minTermMonths, maxTermMonths, graceMonths, collateralType, prepaymentFeeRate, productVersion, validFrom |
| `Customer` | customerNo, name, residentNoEnc(암호문), residentNoBidx(HMAC, 인덱스), birthDate, phoneEnc, addressEnc |
| `LoanAccount` | accountNo(유니크), customerId, productId, productVersion, origin(ORIGINATED / ACQUIRED), repaymentMethod, initialPrincipal, disbursedOn, maturityDate, termMonths, graceMonths, installmentAmount(원리금균등 A, 없으면 null), status, acceleratedOn, lastSettledOn(마지막 이자 정산일), principalBalance, suspenseBalance(가수금), costBalance, unpaidNormalInterest(확정됐으나 미납인 정상이자), unpaidLateInterest(확정·미납 가산이자), assignedStaffId(담당자), scheduleVersion |
| `RateTerm` | accountId, contractRate, validFrom, validTo(null = 현재), reason, recordedAt |
| `DueDayTerm` | accountId, dueDay, validFrom, validTo, reason, recordedAt |
| `Installment` | accountId, scheduleVersion, seq, dueDate, principalDue, plannedInterest, principalPaid, status, settledOn |
| `LedgerEntry` | accountId, type, valueDate(기산일), postedAt, amount, principal, normalInterest, lateInterest, cost, fee, suspense, principalBalanceAfter, preAcqInterest, paymentId, correctionId, reversalOfId. superseded는 저장하지 않는 파생 상태(REVERSAL이 가리키는 거래) — 뷰로 계산 |
| `Payment` | accountId(null 가능), channel, valueDate, receivedAt, amount, depositorName, bankTxId(유니크, 중복 방지), status |
| `UnmatchedDeposit` | paymentId, status, matchedAccountId, matchedAt, matchedBy, refundPaymentId |
| `Acquisition` (N1) | accountId, acquiredOn, acquisitionPrice, seller, originalContractDate, originalRate, originalLateRate, preAcqInterest(양수 전 미수이자 잔액), interestMode(CHARGE / STOP), originalAccelerationDate, limitationStartDate, judgmentConfirmedOn |
| `AccelerationNotice` | accountId, reasonDate, sentOn, deemedArrivalOn, actualArrivalOn, accelerationDate, status |
| `PrepaymentRequest` | accountId, requestedOn, principalAmount, interest, fee, total, status |
| `BusinessDate` | currentDate(단일 행), lastClosedAt |
| `Holiday` | date, name |
| `Staff`, `Role`, `Permission`, `StaffRole` | 9장 + 정책서 9.1 권한표. Staff에 teamId, \`RolePermission\`(roleId, permission, scope: OWN / TEAM / ALL, amountLimit)으로 ‘담당 건 / 팀 전체’와 금액별 승인 표현 |
| `AccessLog` | staffId, accessedAt, ip, action, targetType, targetId, reason |

### 16.3 상태 코드

| 대상 | 값 |
| --- | --- |
| `LoanAccount.status` | NORMAL, DELINQUENT, ACCELERATED, WORKOUT, CHARGED\_OFF, SOLD, PAID\_OFF. N1은 ACCELERATED로 시작 |
| `Installment.status` | PLANNED, PAID, PARTIALLY\_PAID, OVERDUE, REPLACED(재산출로 대체) |
| `LedgerEntry.type` | DISBURSE, ACQUIRE, REPAYMENT, PREPAYMENT, PREPAYMENT\_FEE, COST\_CHARGE, SUSPENSE\_IN, SUSPENSE\_APPLY, WAIVER, ADJUSTMENT, REVERSAL, CHARGE\_OFF, SALE, REPURCHASE, REFUND |
| `Payment.channel` | VIRTUAL\_ACCOUNT, BANK\_TRANSFER, AUTO\_DEBIT, CASH |
| `Payment.status` | RECEIVED, ALLOCATED, UNMATCHED, REVERSED(은행 측 취소), CANCELLED(입력 오류로 실제 입금 없음, R26) |
| `UnmatchedDeposit.status` | OPEN, MATCHED, SPLIT, REFUNDED |
| `AccelerationNotice.status` | PENDING, CANCELLED(상실 전 해소), EXECUTED, REVOKED(소급으로 취소, R5) |
| `PrepaymentRequest.status` | QUOTED, PAID, EXPIRED |

API 경로·요청 형식·화면 배치는 정해 두지 않았습니다. 구현하는 쪽이 설계하고 ADR로 남깁니다.

### 16.4 추가 엔티티·관계·제약

**추가 엔티티**

| 엔티티 | 주요 필드 | 역할 |
| --- | --- | --- |
| `LedgerAllocation` | ledgerEntryId, installmentId(null = 회차 없는 P5·N1·가수금), component(PRINCIPAL / NORMAL\_INTEREST / LATE\_INTEREST / COST / FEE / PRE\_ACQ\_INTEREST / SUSPENSE), amount | 원장 거래 1건이 어느 회차·항목에 얼마 충당됐는지. `Installment.principalPaid`는 이 표의 유효 행 합계로 만드는 파생값 |
| `PaymentCorrection` | correctionNo(유니크), type(CANCEL / MOVE / AMOUNT\_FIX), originalPaymentId, targetAccountId, correctedAmount, reason, requestedBy, approvedBy, status(REQUESTED / APPROVED / APPLIED / REJECTED) | 정책서 R26\~R30의 취소·정정 묶음 |

**관계 (같은 모듈 안에서만 FK, 다른 모듈은 ID만 저장)**

| 부모 | 자식 | 다중성 | FK |
| --- | --- | --- | --- |
| Customer | LoanAccount | 1 : N | — (모듈 다름, ID만) |
| LoanProduct | LoanAccount | 1 : N | — (productId + productVersion 스냅샷) |
| LoanAccount | RateTerm, DueDayTerm, Installment, LedgerEntry, AccelerationNotice, PrepaymentRequest | 1 : N | O |
| LoanAccount | Acquisition | 1 : 0..1 | O |
| LedgerEntry | LedgerAllocation | 1 : N | O |
| Installment | LedgerAllocation | 1 : N | O (nullable) |
| LedgerEntry | LedgerEntry (reversalOfId) | 1 : 0..1 | O |
| Payment | LedgerEntry | 1 : N | — (payment 모듈 → servicing, ID만) |
| Payment | UnmatchedDeposit, PaymentCorrection | 1 : 0..1 / 1 : N | O |

**제약·인덱스**

- 이력 기간: `valid_from DATE NOT NULL`, `valid_to DATE NULL` 두 컬럼으로 저장하고, 겹침은 `btree_gist` 확장 + `EXCLUDE USING gist (account_id WITH =, daterange(valid_from, valid_to) WITH &&)`로 막음
- 유니크: accountNo, customerNo, residentNoBidx, bankTxId, correctionNo, (accountId, scheduleVersion, seq)
- 인덱스: LedgerEntry(accountId, valueDate, id), Installment(accountId, dueDate) WHERE status IN ('PLANNED','PARTIALLY\_PAID','OVERDUE'), LoanAccount(status), Payment(status, valueDate), AccessLog(staffId, accessedAt)
- 상태·유형 컬럼은 `VARCHAR(30)` + `CHECK` 제약 (PostgreSQL enum 타입은 값 추가가 번거로워 쓰지 않음)
- 암호문 컬럼 `VARCHAR(512)`, 블라인드 인덱스 `CHAR(64)`(HMAC-SHA256 hex)
- NULL: 16.2에서 “null 가능”·“없으면”이라고 적은 필드와 상태에 따라 채워지는 필드(acceleratedOn, matchedAt, settledOn 등)만 허용, 나머지는 NOT NULL

## 17. 구현 로드맵

계산 엔진(상환·이자·충당)을 가장 먼저, 테스트와 함께 만드는 것을 권장합니다. 화면과 연계는 그 위에 얼마든지 올릴 수 있지만, 계산 엔진이 틀리면 전체가 무너집니다.

### 1단계: 도메인 코어 (화면 없이)

- [ ] `Money`, `InterestRate`, `DateRange`, `BusinessCalendar` 값 객체와 테스트
- [ ] 분할상환 스케줄 4종 + 자유상환·매입채권 + 엑셀 기대값 비교 테스트
- [ ] 구간 분할 일할 이자 계산 (한편넣기, 정책서 R1·R2·R12)
- [ ] 충당 정책, 연체이자·법정 상한 정책

### 2단계: 여신관리 백엔드

- [ ] 계약·금리·약정일 이력 엔티티, Flyway 스키마
- [ ] 원장(append-only), 입금 처리, 역분개
- [ ] 소급 입금 재생 로직 + 결정성 테스트
- [ ] 미확인 입금, 비용 처리, 일마감 검증

### 3단계: 생애주기·심사

- [ ] 연체·기한이익상실 배치, 채무조정·상각·매각·환매·완제
- [ ] 소멸시효 계산·법조치 관리, 추심 연락 한도 가드
- [ ] 신청 상태 머신, 심사자 배정, 상품 버전, 실행 → 계약 이관(Outbox)

### 4단계: 보안·연계·화면

- [ ] Spring Security 권한 모델, 접근 기록, 컬럼 암호화·블라인드 인덱스
- [ ] 포트 + Mock 어댑터 (신용조회, 본인인증, 가상계좌, 문자)
- [ ] 목록 공통 컴포넌트, 계약 상세 3단 화면
- [ ] 엑셀 일괄 처리 프레임워크

### 5단계: 운영·문서화

- [ ] Docker Compose 배포, CI, 모니터링
- [ ] README: 아키텍처, 핵심 설계 결정(ADR), 계산 로직 검증 방법
- [ ] 포트폴리오 강조점: 소급 재계산, 이력 모델링, 법령 준수 기능(최고금리, 추심 한도), 암호화 검색
