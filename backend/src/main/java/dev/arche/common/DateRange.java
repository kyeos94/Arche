package dev.arche.common;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 반개구간 [from, to). 한편넣기(초일산입·말일불산입).
 */
public record DateRange(LocalDate from, LocalDate to) {

	public DateRange {
		if (to.isBefore(from)) {
			throw new IllegalArgumentException("기간 역전: " + from + " ~ " + to);
		}
	}

	public static DateRange of(LocalDate from, LocalDate to) {
		return new DateRange(from, to);
	}

	public long days() {
		return ChronoUnit.DAYS.between(from, to);
	}

	public boolean contains(LocalDate date) {
		return !date.isBefore(from) && date.isBefore(to);
	}

	public boolean overlaps(DateRange other) {
		return from.isBefore(other.to) && other.from.isBefore(to);
	}

	public boolean isEmpty() {
		return from.equals(to);
	}
}
