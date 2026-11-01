package dev.arche.calendar.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** 휴일 지정·해제 가능 날짜 (R73) */
class HolidayAdminRulesTest {

	private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 11, 2); // 월요일

	@Test
	void 업무일자_다음_날부터_3년_뒤까지의_평일만() {
		assertThatCode(() -> HolidayAdminService.check(LocalDate.of(2026, 11, 3), BUSINESS_DATE))
				.doesNotThrowAnyException();
		assertThatCode(() -> HolidayAdminService.check(LocalDate.of(2029, 11, 2), BUSINESS_DATE))
				.doesNotThrowAnyException();
		assertThatThrownBy(() -> HolidayAdminService.check(BUSINESS_DATE, BUSINESS_DATE)).hasMessageContaining("다음 날부터");
		assertThatThrownBy(() -> HolidayAdminService.check(LocalDate.of(2026, 10, 9), BUSINESS_DATE))
				.hasMessageContaining("다음 날부터");
		assertThatThrownBy(() -> HolidayAdminService.check(LocalDate.of(2029, 11, 5), BUSINESS_DATE))
				.hasMessageContaining("3년");
		assertThatThrownBy(() -> HolidayAdminService.check(LocalDate.of(2026, 11, 7), BUSINESS_DATE))
				.hasMessageContaining("토·일요일");
		assertThatThrownBy(() -> HolidayAdminService.check(LocalDate.of(2026, 11, 8), BUSINESS_DATE))
				.hasMessageContaining("토·일요일");
	}
}
