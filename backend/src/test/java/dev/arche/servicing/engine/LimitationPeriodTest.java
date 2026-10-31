package dev.arche.servicing.engine;

import static java.time.LocalDate.parse;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** G13. 소멸시효 (G8 대출) */
class LimitationPeriodTest {

	@Test
	void G13_기산_중단_판결() {
		var accel = parse("2026-08-13");
		assertThat(LimitationPeriod.completionDate(accel, List.of(), null)).isEqualTo(parse("2031-08-13"));
		assertThat(LimitationPeriod.alertDates(parse("2031-08-13")))
				.containsExactly(parse("2031-02-14"), parse("2031-05-15"), parse("2031-07-14"));
		assertThat(LimitationPeriod.completionDate(accel, List.of(parse("2027-03-02")), null))
				.isEqualTo(parse("2032-03-02"));
		assertThat(LimitationPeriod.completionDate(accel, List.of(parse("2027-03-02")), parse("2027-09-01")))
				.isEqualTo(parse("2037-09-01"));
	}

	@Test
	void 정책서_예시_초일불산입() {
		assertThat(LimitationPeriod.completionDate(parse("2026-06-26"), List.of(), null))
				.isEqualTo(parse("2031-06-26"));
	}
}
