# CLAUDE.md

## 프로젝트
가상의 대부업체 '예시대부'의 여신관리 시스템 **Arche** (포트폴리오).

- 백엔드 `backend/`: Java 21, Spring Boot 4.1.2, Spring Modulith 2.1.1, Hibernate 7(JPA), Flyway, PostgreSQL 18, Gradle(Kotlin DSL). 패키지 `dev.arche`
- 프런트엔드 `frontend/`: Vite, React 19(React Compiler), TanStack Query, shadcn/ui, react-hook-form, zod 4, Vitest
- 락은 PostgreSQL 행 잠금(`FOR UPDATE`), 세션은 서블릿 세션, 캐시는 Caffeine, 배치 중복 방지는 ShedLock JDBC. Redis·QueryDSL·RabbitMQ는 쓰지 않음 (도입하려면 ADR 작성 후 승인)
- 로컬은 PostgreSQL 18을 직접 사용(docker-compose 없음). Docker가 필요한 통합 테스트(Testcontainers, `@Tag("integration")`)는 GitHub Actions에서만 실행. 로컬 `./gradlew test`는 단위 테스트만
- 문서와 다른 기술 선택은 docs/adr/0001-tech-stack.md 표 참고

## 기준 문서 (충돌 시 이 순서로 우선)
1. docs/policy.md — 모든 업무 규칙과 숫자. 9~10장(R1~R38)이 본문보다 우선
2. docs/golden-cases.md — 계산 기대값. 테스트가 이 값과 다르면 코드를 고칠 것 (기대값 수정 금지)
3. docs/guide.md — 설계 방향과 일반론. 수치·규칙이 policy.md와 다르면 policy.md를 따를 것. 16.2~16.3의 엔티티 필드·상태 코드는 스키마 기준
   - guide.md의 기술 스택(Boot 3, Redis, Thymeleaf 등)은 위 '프로젝트'의 스택으로 대체 (docs/adr/0001). guide.md의 그림은 내보내기에서 빠지므로 16.2~16.4 표가 스키마 기준

## 범위
- 구현 범위는 docs/mvp.md 1장 표가 전부. 5장 '범위 밖 기능'은 구현하지 말고, 필요해 보이면 README 확장 지점에 적기만 할 것

## 판단 기준 (언제 멈추고 언제 진행하는가)
- 업무 규칙(금액·날짜·상태가 바뀌는 규칙)이 policy.md에 없거나 두 가지로 읽히면: 구현을 멈추고 질문. 답을 받으면 policy.md 9장에 R번호로 추가한 뒤 진행
- 기술 결정(테이블·컬럼 이름, API 형식, 화면 배치, 라이브러리): 스스로 정하고 docs/adr/에 기록, 작업 보고에 포함
- golden 기대값과 다르면: 코드를 의심. 기대값이 틀렸다고 판단되면 계산 근거를 보여주고 사용자 승인 후에만 수정

## 절대 규칙
- 금액은 BigDecimal 또는 long(원). double/float 금지. 반올림은 Money 클래스에서만
- 날짜는 LocalDate, 기간은 반개구간 [from, to). 시간대 Asia/Seoul
- 기준일은 업무일자(BusinessDate)와 주입된 Clock에서만. LocalDate.now() 직접 호출 금지
- 이자는 정산 이벤트에서만 원장에 확정 (policy.md 9장 R1). 일일 배치의 미수이자는 조회용
- 원장(LedgerEntry)은 UPDATE/DELETE 금지. 정정은 역분개 거래로
- 금리·약정일은 이력 엔티티(RateTerm, DueDayTerm)에서 조회. 계약에 현재값 컬럼을 두지 말 것
- 다른 모듈의 Repository를 직접 참조하지 말 것 (Spring Modulith `ApplicationModules.verify()` + ArchUnit으로 검사)
- 고객 식별번호는 EncryptedStringConverter + 블라인드 인덱스. 로그·toString에 평문 출력 금지
- 실제 회사·기관의 이름, 전문 형식, 코드 체계를 넣지 말 것. 외부 연계는 포트 + Mock 어댑터만

## 작업 방식
- 계산 로직은 테스트 먼저: golden CSV(`backend/src/test/resources/golden/`)를 읽는 @ParameterizedTest를 작성하고 통과시킬 것
- 한 번에 한 모듈. 작업 후 `./gradlew test` 전체 통과 확인
- 스키마 변경은 Flyway 마이그레이션으로만 (ddl-auto=validate)

## 명령
- 백엔드 단위 테스트: `cd backend && ./gradlew test`
- 백엔드 통합 테스트(CI): `cd backend && ./gradlew integrationTest`
- 실행: 로컬 PostgreSQL 18에 `arche` DB 생성 후 `cd backend && ./gradlew bootRun` (접속 정보는 환경변수 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`)
- 프런트: `cd frontend && npm ci && npm run dev` / `npm test` / `npm run build`
