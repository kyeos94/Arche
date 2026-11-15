package dev.arche.operation.internal;

import dev.arche.servicing.LoanAccounts.JudgeFailure;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

/**
 * 일마감 판정 실패 기록 (R76). 추가만 하고, 해결 표시는 {@link DailyClosingFailureRepository#resolve}로만 한다
 * (DB 트리거 daily_closing_failure_guard가 그 밖의 수정·삭제를 막는다).
 */
@Entity
@Immutable
@Table(name = "daily_closing_failure")
class DailyClosingFailure {

	static final int CODE_MAX = 60;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	private LocalDate businessDate;
	private long accountId;
	@Enumerated(EnumType.STRING)
	private JudgeFailure.Kind errorKind;
	private String errorCode;
	private String message;
	private OffsetDateTime recordedAt;
	private @Nullable OffsetDateTime resolvedAt;
	private @Nullable String resolvedBy;

	protected DailyClosingFailure() {
	}

	DailyClosingFailure(LocalDate businessDate, JudgeFailure f, OffsetDateTime recordedAt) {
		this.businessDate = businessDate;
		this.accountId = f.accountId();
		this.errorKind = f.kind();
		this.errorCode = f.code().length() <= CODE_MAX ? f.code() : f.code().substring(0, CODE_MAX);
		this.message = f.message();
		this.recordedAt = recordedAt;
	}

	Long id() {
		return id;
	}

	LocalDate businessDate() {
		return businessDate;
	}

	long accountId() {
		return accountId;
	}

	JudgeFailure.Kind errorKind() {
		return errorKind;
	}

	String errorCode() {
		return errorCode;
	}

	String message() {
		return message;
	}

	OffsetDateTime recordedAt() {
		return recordedAt;
	}

	@Nullable
	OffsetDateTime resolvedAt() {
		return resolvedAt;
	}

	@Nullable
	String resolvedBy() {
		return resolvedBy;
	}

	boolean resolved() {
		return resolvedAt != null;
	}
}
