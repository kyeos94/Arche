package dev.arche.origination.internal;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.Immutable;

/** 신청 상태 전이 기록: 이전·이후 상태, 처리자, 사유, 시각 (guide 2.2) */
@Entity
@Immutable
@Table(name = "application_status_history")
class ApplicationStatusHistory {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	private long applicationId;
	private String fromStatus;
	private String toStatus;
	private String actor;
	private String reason;
	private OffsetDateTime changedAt;

	protected ApplicationStatusHistory() {
	}

	ApplicationStatusHistory(long applicationId, String fromStatus, String toStatus, String actor, String reason,
			OffsetDateTime changedAt) {
		this.applicationId = applicationId;
		this.fromStatus = fromStatus;
		this.toStatus = toStatus;
		this.actor = actor;
		this.reason = reason;
		this.changedAt = changedAt;
	}

	String fromStatus() {
		return fromStatus;
	}

	String toStatus() {
		return toStatus;
	}

	String actor() {
		return actor;
	}

	String reason() {
		return reason;
	}

	OffsetDateTime changedAt() {
		return changedAt;
	}
}
