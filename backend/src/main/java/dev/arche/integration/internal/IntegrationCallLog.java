package dev.arche.integration.internal;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 대외 연계 요청 이력 (guide 11.2 IntegrationLog): 기관, 업무, 요청·응답 요약, 소요시간, 결과코드.
 * 요약에는 개인정보를 마스킹해서 넣는다(호출 어댑터 책임). 추가 전용.
 */
@Entity
@Immutable
@Table(name = "integration_call_log")
class IntegrationCallLog {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	private String institution;
	private String operation;
	private String summary;
	private long elapsedMs;
	private String resultCode;
	private boolean success;
	private OffsetDateTime calledAt;

	protected IntegrationCallLog() {
	}

	IntegrationCallLog(String institution, String operation, String summary, long elapsedMs, String resultCode,
			boolean success, OffsetDateTime calledAt) {
		this.institution = institution;
		this.operation = operation;
		this.summary = summary.length() > 300 ? summary.substring(0, 300) : summary;
		this.elapsedMs = elapsedMs;
		this.resultCode = resultCode;
		this.success = success;
		this.calledAt = calledAt;
	}

	Long getId() {
		return id;
	}

	String institution() {
		return institution;
	}

	String operation() {
		return operation;
	}

	String summary() {
		return summary;
	}

	long elapsedMs() {
		return elapsedMs;
	}

	String resultCode() {
		return resultCode;
	}

	boolean success() {
		return success;
	}

	OffsetDateTime calledAt() {
		return calledAt;
	}
}

interface IntegrationCallLogRepository extends JpaRepository<IntegrationCallLog, Long> {

	Page<IntegrationCallLog> findByInstitutionContainingOrderByIdDesc(String institution, Pageable pageable);

	List<IntegrationCallLog> findTop20ByInstitutionOrderByIdDesc(String institution);
}

/**
 * 어댑터가 연계 호출을 감싸 이력을 남긴다. 업무 트랜잭션이 롤백돼도 연계 이력은 남도록 별도 트랜잭션.
 */
@Component
class CallLogger {

	/** 결과 해석: 결과코드와 성공 여부 */
	record Outcome(String code, boolean success) {
	}

	private final IntegrationCallLogRepository logs;
	private final TransactionTemplate requiresNew;
	private final java.time.Clock clock;

	CallLogger(IntegrationCallLogRepository logs, PlatformTransactionManager tx, java.time.Clock clock) {
		this.logs = logs;
		this.clock = clock;
		this.requiresNew = new TransactionTemplate(tx);
		this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	<T> T call(String institution, String operation, String summary, Supplier<T> action,
			Function<T, Outcome> outcome) {
		OffsetDateTime at = OffsetDateTime.now(clock);
		long start = System.nanoTime();
		T result;
		try {
			result = action.get();
		} catch (RuntimeException e) {
			record(institution, operation, summary + " / 예외 " + e.getClass().getSimpleName(), start, "EXCEPTION",
					false, at);
			throw e;
		}
		Outcome o = outcome.apply(result);
		record(institution, operation, summary, start, o.code(), o.success(), at);
		return result;
	}

	private void record(String institution, String operation, String summary, long start, String code,
			boolean success, OffsetDateTime at) {
		long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
		requiresNew.executeWithoutResult(s -> logs.save(new IntegrationCallLog(institution, operation, summary, ms,
				code, success, at)));
	}
}
