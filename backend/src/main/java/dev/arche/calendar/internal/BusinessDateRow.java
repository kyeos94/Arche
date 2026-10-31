package dev.arche.calendar.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "business_date")
class BusinessDateRow {

	@Id
	private Short id;

	@Column(name = "current_date_", nullable = false)
	private LocalDate currentDate;

	private OffsetDateTime lastClosedAt;

	@Column(nullable = false)
	private OffsetDateTime updatedAt;

	@Version
	private long version;

	protected BusinessDateRow() {
	}

	LocalDate currentDate() {
		return currentDate;
	}

	void advanceTo(LocalDate next, OffsetDateTime now) {
		this.currentDate = next;
		this.lastClosedAt = now;
		this.updatedAt = now;
	}
}
