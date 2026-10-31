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
# 로컬 PostgreSQL 18에 arche DB 생성 (createdb arche)
cd backend && ./gradlew bootRun           # http://localhost:8080
cd frontend && npm ci && npm run dev      # http://localhost:5173
```

## 진행 기록

구현 단계마다 "무엇을 왜 이렇게 만들었는지"를 아래에 남깁니다.

## 확장 지점 (범위 밖)

| 기능 | 확장 지점 |
| --- | --- |
| 한 고객의 여러 계약 일괄 입금 배분, 보증인·제3자 대위변제 | payment 모듈의 배분 전략 인터페이스 |
| 사망(상속), 개인회생·파산, 신용회복위원회 채무조정 | collection 모듈의 추심 중지 플래그 + 금리 이력 변경 |
| 법조치 상세(지급명령·압류·경매), 경매 배당금·공탁금 입금 | LegalAction 엔티티 + 비용(COST) 충당 |
| 상각 확정, 매각·환매, 매각 후 입금 이전 | LoanAccount 상태 전이 + 원장 CHARGE_OFF·SALE 거래 |
| 신용정보 보고(정보집중), 신용조회, 본인인증, 펌뱅킹 송금 | integration 모듈의 포트 추가 |
| 문자·팩스·우편, 연락 타임라인 패널 | communication 모듈 |
| 담보·보증인 관리, 담보 시세 연계 | servicing 모듈의 하위 엔티티 |
| 보고서(일보 상세, 연체 전이율, 대손충당금), 회계 분개 | reporting 모듈 + JournalMapper |
| 심사 상세(서류 징구, 자동 거절 규칙, 중개수수료 정산), 플랫폼 접수 | origination 모듈 |
| 수기 조정 거래, 상계, 완제·매각 후 취소(상태 되돌리기) | 원장 ADJUSTMENT 거래 + 결재 |
