package dev.arche.calendar.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

@Entity
@Table(name = "holiday")
class HolidayRow {

	@Id
	private LocalDate holidayDate;

	@Column(nullable = false)
	private String name;

	protected HolidayRow() {
	}

	LocalDate date() {
		return holidayDate;
	}
}
