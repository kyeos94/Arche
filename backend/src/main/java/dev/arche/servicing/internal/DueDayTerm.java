package dev.arche.servicing.internal;

import dev.arche.common.jpa.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 약정일 이력. validFrom은 새 약정일이 처음 적용되는 납입일 (R21의 6개월 기산점).
 */
@Entity
@Table(name = "due_day_term")
class DueDayTerm extends BaseEntity {

	private long accountId;
	private int dueDay;
	private LocalDate validFrom;
	private LocalDate validTo;
	private LocalDate requestedOn;
	private String reason;
	private OffsetDateTime recordedAt;

	protected DueDayTerm() {
	}

	DueDayTerm(long accountId, int dueDay, LocalDate validFrom, LocalDate requestedOn, String reason,
			OffsetDateTime recordedAt) {
		this.accountId = accountId;
		this.dueDay = dueDay;
		this.validFrom = validFrom;
		this.requestedOn = requestedOn;
		this.reason = reason;
		this.recordedAt = recordedAt;
	}

	void closeAt(LocalDate to) {
		this.validTo = to;
	}

	int dueDay() {
		return dueDay;
	}

	LocalDate validFrom() {
		return validFrom;
	}

	LocalDate validTo() {
		return validTo;
	}

	LocalDate requestedOn() {
		return requestedOn;
	}

	String reason() {
		return reason;
	}

	OffsetDateTime recordedAt() {
		return recordedAt;
	}
}
