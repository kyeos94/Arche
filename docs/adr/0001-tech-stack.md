# ADR 0001. 기술 스택과 문서 대비 차이

- 상태: 채택 (2026-10-31)

## 맥락
guide.md·mvp.md는 Spring Boot 3, `com.example.loan`, Redis, Thymeleaf 등을 예시로 든다. 직전 프로젝트에서 검증한 스택을 그대로 쓰는 편이 학습 비용이 적다.

## 결정

| 문서 내용 | Arche에서의 처리 |
| --- | --- |
| Spring Boot 3, `com.example.loan` | Boot 4.1.2, Modulith 2.1.1, 패키지 `dev.arche` |
| docker-compose.yml (postgres, redis) | 로컬은 PostgreSQL 18 직접 사용, Docker(Testcontainers) 테스트는 CI에서만 |
| Redis·Redisson 락, 세션, 캐시 | PostgreSQL 행 잠금(`FOR UPDATE`), 서블릿 세션, Caffeine, ShedLock JDBC |
| Thymeleaf 화면 | REST API + React 19 관리자 화면 |
| QueryDSL, RabbitMQ | 지금은 넣지 않고, 필요할 때 ADR을 쓰고 승인받은 뒤 도입 |
| guide.md의 그림 | 내보내기에서 빠지므로 16.2~16.4 표를 스키마 기준으로 사용 |

- 백엔드: Java 21, Hibernate 7(JPA), Flyway, Gradle Kotlin DSL
- 모듈 경계는 Spring Modulith 최상위 패키지(`common`, `product`, `servicing`, `payment`, `collection`, `customer`, `staff`, `origination`, `integration`, `operation`)로 표현하고 `ApplicationModules.verify()`로 검사
- 프런트엔드: Vite, React 19(React Compiler), TanStack Query, shadcn/ui, react-hook-form, zod 4, Vitest
- 저장소 구조: `backend/`, `frontend/` 모노레포
- 테스트: 계산 로직은 순수 단위 테스트(golden CSV). DB가 필요한 통합 테스트는 `@Tag("integration")` + Testcontainers, GitHub Actions에서만 실행

## 결과
- guide.md의 Thymeleaf·Bootstrap 관련 내용은 React 컴포넌트로 대체
- 다중 서버 배치 중복 실행은 ShedLock(JDBC)로 막음
