# Arche — 대부 여신관리 시스템 (포트폴리오)

가상의 대부업체 **예시대부(가상)** 의 여신관리(계약·이자·입금·연체) 시스템입니다.
범위는 "좁고 깊게" — 계약 하나의 돈 흐름(이자·입금·취소·연체)을 실무 예외까지 정확하게 만들고,
나머지 업무는 뼈대 하나만 두거나 아래 확장 지점으로만 남깁니다.

| 문서 | 내용 |
| --- | --- |
| [docs/policy.md](docs/policy.md) | 가상 회사 정책서 — 모든 업무 규칙과 숫자 (R1~R33) |
| [docs/golden-cases.md](docs/golden-cases.md) | 계산 검증 사례 G1~G28 — 테스트 기대값 |
| [docs/guide.md](docs/guide.md) | 구현 가이드 — 설계 방향 |
| [docs/mvp.md](docs/mvp.md) | MVP 범위 |
| [docs/adr/](docs/adr/) | 설계 결정 기록 |

## 기술 스택

- 백엔드: Java 21, Spring Boot 4.1.2, Spring Modulith 2.1.1, Hibernate 7(JPA), Flyway, PostgreSQL 18 (패키지 `dev.arche`)
- 락·세션·캐시: PostgreSQL 행 잠금(`FOR UPDATE`), 서블릿 세션, Caffeine, ShedLock JDBC
- 프런트엔드: Vite, React 19(React Compiler), TanStack Query, shadcn/ui, react-hook-form, zod 4, Vitest
- 통합 테스트(Testcontainers)는 GitHub Actions에서만 실행
- 문서(guide.md)와 다른 선택은 [ADR 0001](docs/adr/0001-tech-stack.md)

## 실행

```bash
# 1) 로컬 PostgreSQL 18에 DB와 사용자 생성 (기본값: arche / arche / arche)
createdb arche
# 2) 백엔드 — 기동 시 Flyway가 스키마·상품·휴일을 만들고, 데모 직원·계약을 넣는다
cd backend && ./gradlew bootRun           # http://localhost:8080
# 3) 프런트엔드 — /api는 8080으로 프록시
cd frontend && npm ci && npm run dev      # http://localhost:5173
```

포트가 다른 프로젝트와 겹치면 바꿔서 띄웁니다.

```bash
cd backend && ./gradlew bootRun --args='--server.port=8081'
cd frontend && ARCHE_WEB_PORT=5174 ARCHE_API_URL=http://localhost:8081 npm run dev   # http://localhost:5174
```

접속 정보는 환경변수 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`로 바꿉니다. 운영에서는 `ARCHE_DEMO=false`와 암호화 키
(`ARCHE_FIELD_KEY_V1`, `ARCHE_INDEX_KEY`, Base64 32바이트), `ARCHE_VA_SECRET`, `ARCHE_ADMIN_PASSWORD`를 반드시 넣습니다.

### 데모

업무일자는 **2026-06-12**에서 시작하고, 시스템관리자가 일마감을 누를 때마다 하루씩 넘어갑니다.
데모 직원은 모두 비밀번호 `arche1234!`입니다.

| 아이디 | 역할 | 아이디 | 역할 |
| --- | --- | --- | --- |
| `uw` | 심사역 | `collead` | 채권팀장 (2팀) |
| `uwlead` | 심사팀장 | `teller` | 출납 |
| `col1` | 채권담당 (2팀) | `head` | 본부장 |
| `col2` | 채권담당 (3팀) | `admin` | 시스템관리자 |
| `demo` | **데모 전용: 모든 메뉴** | | |

`demo`는 정책서 역할이 아닌 데모 전용 계정입니다(`DEMO_ALL`). 데모 모드에서만 만들어지고, 데모 모드를 끄면 로그인할 수 없습니다. 작성자 ≠ 승인자 검사는 그대로라 `demo`가 요청한 마감 후 정정은 다른 계정(`collead`)이 승인해야 합니다.

| 계약 | 내용 | 화면에서 해 볼 것 |
| --- | --- | --- |
| A 홍길동 (P3, G2 조건) | 1회차만 납부 → 연체 18일 | 미확인 입금(05-26, 1,135,616원)을 A에 매칭 → 정상 (**G11**). 그 입금을 삭제 → 연체 부활 (**G27**). 연체 상태에서 채무조정 견적 → 미감면 이자 입금 → 체결 (**G23**) |
| B 김철수 (P5) | 잔액 3,000,000원 | A의 입금을 B로 정정 이동 → 이자 9,616원, 원금 1,126,000원 충당 (**G28**) |
| C 이영희 (P1, G1 조건) | 5회차까지 정상 | 금리 인하(G24), 약정일 변경(G25), 일부 중도상환(G20) |
| D 박민수 (P5, G15 조건) | 자유상환 | 수시 상환, 금액 오입력 당일 정정(G26) |
| E 최매입 (N1, G17 조건) | 매입채권 | 충당 순서, 누적 회수율. 추심 탭에서 분할납부 약속 등록·판정(**G19**), 소멸시효 확인(**G13**) |

상단 바의 **통합 검색**창에 계약번호·이름·주민등록번호·휴대폰번호를 넣으면 조회 범위 안의 계약을 찾습니다(결과가 하나면 바로 상세로 이동). 주민등록번호·휴대폰번호는 블라인드 인덱스로 일치 검색하고 결과에는 마스킹된 값만 보입니다.

계약 상세의 **추심** 탭에서 연락 유예를 등록하거나 연락을 기록하면 직전 7일 7회 한도·연락 가능 시간·소멸시효 완성 여부로 허용·차단을 판정합니다(**G14**). 소멸시효 임박·약속 불이행 알림은 일마감 때 만들어져 **추심 알림** 화면에 나옵니다.

## 테스트

```bash
cd backend && ./gradlew test               # 단위: golden G1~G28, 모듈 경계, 권한표 (Docker 불필요)
cd backend && ./gradlew integrationTest    # 통합: Testcontainers (CI)
cd frontend && npm test
```

- `GoldenScheduleTest`: [golden/schedules.csv](backend/src/test/resources/golden/schedules.csv) 65행(G1~G4, G20, G24, G25)을 회차 단위로 대조
- `GoldenScenarioTest`·`PolicyRuleTest`: G5~G28, R3·R5·R9·R23·R29·R32·R33 등 정책 분기, 재생 결정성
- `ArchitectureTest`: Spring Modulith 모듈 경계, 다른 모듈 Repository 참조 금지, `LocalDate.now()` 금지, double/float 금지, 엔진의 Spring·JPA 비의존
- `SecurityIT`·`PaymentCorrectionIT`·`WorkoutIT`·`CollectionIT`·`DeferralMessageIT`·`IntegrationFlowsIT`: 연락 유예·자동 문자, G13·G14·G19 저장·판정, 채무조정 결재권·체결 거절(R23), 권한표 허용·차단, G11→G27→G28 REST 흐름, 웹훅 중복 통지, 1,000행 일괄 등록, 신청→계약 이관, 원장 UPDATE·DELETE 차단

## 진행 기록 — 무엇을 왜 이렇게 만들었나

1. **계산 엔진을 이벤트 재생 방식으로** ([ADR 0002](docs/adr/0002-replay-engine.md)) — 소급 입금·입금 취소·정정 이동·금리 변경이 모두
   "과거 어느 날부터 다시 계산"이라서, 계약 조건과 이벤트 목록을 받아 하루씩 재생하는 순수 함수 하나로 모았다. 취소는 이벤트를 비활성으로
   바꾸고 다시 재생하는 것이고, 저장 계층은 재생 결과와 저장된 원장을 비교해 달라진 거래만 역분개 + 새 거래로 남긴다. 원장은 DB 트리거로
   UPDATE·DELETE를 막았다.
2. **이자 구간은 하루 단위 기준이 바뀌는 곳에서만 자른다** — 정상이자(원금 × 약정이율)와 가산이자(미납 원금 또는 상실 후 전체 잔액 × 가산분)를
   각각 "기준금액·이율이 같은 연속된 날"로 묶어 구간마다 절사한다(R2·R12). 미납된 약정일이 구간 경계가 되지 않는 것이 자연스럽게 나온다.
3. **기한이익상실 통지는 저장된 사실로 다시 넣는다** — 재생은 결정적이지만 통지는 "그때 보낸 것"이라 재생이 바꾸면 안 된다. 저장된 통지를
   엔진 입력으로 넣고, 재생 결과 사유가 없어지면 REVOKED(R5), 과거에 생겼어야 할 새 사유는 처리일로 미룬다(R29).
4. **모듈 경계** — servicing은 payment의 입금 테이블을 읽지 않고 자기 `account_event`에 재생 입력을 보관한다. 신청→계약은 Modulith 이벤트
   발행 기록(Outbox)으로 잇는다. 경계는 `ApplicationModules.verify()`와 ArchUnit이 검사한다.
5. **채무조정도 재생 이벤트로** (G23) — 체결일까지 이자를 확정해 감면하고, 그날 받은 입금으로 미감면 이자가 다 채워지면 조정 원금으로
   원리금균등 스케줄을 새로 만든다. 못 채우면 체결하지 않고(R23) API가 트랜잭션째 되돌린다. 조정 후 3회차 연속 미납이면 감면액을
   ADJUSTMENT 거래로 되살리고 기한이익상실로 복귀한다(R9). 감면 항목별 결재권(연체이자 담당자, 정상이자 팀장, 원금 본부장)은 API에서 검사한다.
6. **추심은 기록은 저장하고 판정은 다시 계산** (G13·G14·G19) — 연락 기록은 차단된 시도까지 남기되 한도에는 허용·예약된 연락만 센다.
   분할납부 약속 판정과 소멸시효 완성일은 저장하지 않고 입금 내역(취소 반영)으로 매번 다시 계산해, 소급 입금·입금 취소가 있어도
   결과가 어긋나지 않게 했다. 저장하는 것은 약속 해제·알림처럼 "그때 한 일"뿐이며 일마감에서 확정한다.
7. **자동 문자는 만들 때가 아니라 보낼 때 판정** — 일마감이 다음 날 08:00 발송분(약정일 3일 전 납입 안내, 연체 1·7·30일째,
   완제 안내)을 만들어 두고, 발송기가 연락 가능 시간 안에서 발송 직전에 7일 7회·연락 유예·소멸시효·약속 이행 여부를 다시 본다.
   밤사이 입금으로 연체가 풀리면 연체 문자는 건너뛴다. 실제 발송은 `MessagingPort` Mock 어댑터가 로그만 남긴다.
8. **권한표를 코드 행렬로** ([ADR 0004](docs/adr/0004-security.md)) — 정책서 9.1 표를 `Permission` enum으로 옮기고, 표 자체를 테스트 데이터로 대조한다.

## 확장 지점 (범위 밖)

| 기능 | 확장 지점 |
| --- | --- |
| 한 고객의 여러 계약 일괄 입금 배분, 보증인·제3자 대위변제 | payment 모듈의 배분 전략 인터페이스 |
| 사망(상속), 개인회생·파산, 신용회복위원회 채무조정 | collection 모듈의 추심 중지 플래그 + 금리 이력 변경 |
| 법조치 상세(지급명령·압류·경매), 경매 배당금·공탁금 입금 | LegalAction 엔티티 + 비용(COST) 충당 |
| 상각 확정, 매각·환매, 매각 후 입금 이전 | LoanAccount 상태 전이 + 원장 CHARGE_OFF·SALE 거래 |
| 신용정보 보고(정보집중), 신용조회, 본인인증, 펌뱅킹 송금 | integration 모듈의 포트 추가 |
| 문자 실제 발송(기관 연계)·팩스·우편, 연락 타임라인 패널 | integration `MessagingPort` 실제 어댑터 + communication 모듈 |
| 담보·보증인 관리, 담보 시세 연계 | servicing 모듈의 하위 엔티티 |
| 보고서(일보 상세, 연체 전이율, 대손충당금), 회계 분개 | reporting 모듈 + JournalMapper |
| 심사 상세(서류 징구, 자동 거절 규칙, 중개수수료 정산), 플랫폼 접수 | origination 모듈 |
| 수기 조정 거래, 상계, 완제·매각 후 취소(상태 되돌리기) | 원장 ADJUSTMENT 거래 + 결재 |
| 매입채권 채무조정·조정 완제, 매입채권 연체 문자(R38로 현재는 보내지 않음, 기준일 정의 필요) | collection |
| 대량 일마감 성능 | 마지막 마감 스냅샷부터 재생 |
