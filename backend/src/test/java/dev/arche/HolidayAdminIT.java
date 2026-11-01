package dev.arche;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arche.calendar.BusinessDates;
import dev.arche.support.IntegrationTest;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

/** 휴일 관리 (R73) */
class HolidayAdminIT extends IntegrationTest {

	@Autowired
	BusinessDates businessDates;

	@Test
	void 평일을_휴일로_지정하면_달력에_바로_반영되고_해제하면_되돌아가며_이력이_남는다() throws Exception {
		MockHttpSession admin = login("SYSTEM_ADMIN");
		// 다른 테스트의 계산에 영향을 주지 않도록 2년 뒤의 평일을 쓰고, 끝에 해제한다
		LocalDate date = businessDates.today().plusYears(2);
		while (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY
				|| !businessDates.calendar().isBusinessDay(date)) {
			date = date.plusDays(1);
		}

		JsonNode year = ok(postJson(admin, "/api/admin/holidays", Map.of("date", date.toString(), "name", "임시공휴일")));
		assertThat(year.get("holidays").toString()).contains(date.toString()).contains("임시공휴일");
		assertThat(businessDates.calendar().isBusinessDay(date)).isFalse();
		assertThat(businessDates.calendar().onOrNextBusinessDay(date)).isAfter(date);

		// 같은 날 다시 지정, 지난 날짜, 주말은 거절
		assertThat(status(admin, "/api/admin/holidays", Map.of("date", date.toString(), "name", "중복"))).isEqualTo(422);
		assertThat(status(admin, "/api/admin/holidays",
				Map.of("date", businessDates.today().toString(), "name", "오늘"))).isEqualTo(422);

		ok(postJson(admin, "/api/admin/holidays/removal", Map.of("date", date.toString())));
		assertThat(businessDates.calendar().isBusinessDay(date)).isTrue();

		JsonNode changes = ok(getJson(admin, "/api/admin/holidays/changes?limit=2"));
		assertThat(changes.get(0).get("action").asString()).isEqualTo("REMOVE");
		assertThat(changes.get(1).get("action").asString()).isEqualTo("ADD");
		assertThat(changes.get(1).get("date").asString()).isEqualTo(date.toString());
	}

	@Test
	void 관리자가_아니면_403() throws Exception {
		assertThat(getJson(login("HEAD"), "/api/admin/holidays?year=2026").andReturn().getResponse().getStatus())
				.isEqualTo(403);
		JsonNode year = ok(getJson(login("SYSTEM_ADMIN"), "/api/admin/holidays?year=2026"));
		assertThat(year.get("holidays").size()).isGreaterThanOrEqualTo(15);
	}

	private int status(MockHttpSession session, String url, Map<String, Object> body) throws Exception {
		return postJson(session, url, body).andReturn().getResponse().getStatus();
	}
}
