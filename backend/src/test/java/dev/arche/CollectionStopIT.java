package dev.arche;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arche.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

/**
 * 추심 중지 (R45·R46): 고객 단위로 모든 열린 계약에 걸림, 연락 차단, 개인회생 이자 부과 중단(소급), 해제, 공적 채무조정(가상) 조정 금리.
 */
class CollectionStopIT extends IntegrationTest {

	@Test
	void 개인회생_추심_중지와_이자_부과_중단_해제() throws Exception {
		businessDate("2026-09-01");
		MockHttpSession head = login("HEAD");
		MockHttpSession lead = login("COLLECTION_LEAD");
		String collectorId = newStaff("COLLECTOR", 2, false);
		MockHttpSession collector = loginAs(collectorId);
		long staff = staffId(collectorId);
		long customer = customer(head, "회생");
		long a = openLoan(head, customer, "P5", 3_000_000, "19.5", 12, 0, 20, "2026-08-01", staff);
		long b = openLoan(head, customer, "P1", 5_000_000, "19.9", 12, 0, 10, "2026-08-01", staff);
		long before = detail(head, a).get("outstandingNormalInterest").asLong();
		assertThat(before).isPositive();

		var body = Map.of("reason", "REHABILITATION", "effectiveDate", "2026-08-20", "memo", "개시결정 통지 수령 (가상 사건)");
		// 채권담당은 등록할 수 없다
		assertThat(postJson(collector, "/api/loans/" + a + "/collection-stops", body).andReturn().getResponse()
				.getStatus()).isEqualTo(403);
		JsonNode rows = ok(postJson(lead, "/api/loans/" + a + "/collection-stops", body));
		// 같은 고객의 열린 계약 모두에 걸린다
		assertThat(rows).hasSize(2);
		assertThat(rows.get(0).get("interestEventId").isNull()).isFalse();

		// 08-20부터 이자를 붙이지 않는다 (소급 재계산)
		long after = detail(head, a).get("outstandingNormalInterest").asLong();
		assertThat(after).isLessThan(before);
		JsonNode events = ok(getJson(head, "/api/loans/" + a + "/history")).get("events");
		boolean suspension = false;
		for (JsonNode e : events) {
			suspension |= e.get("type").asString().equals("INTEREST_SUSPENSION");
		}
		assertThat(suspension).isTrue();

		// 다른 계약에도 추심 연락이 막힌다
		JsonNode blocked = ok(postJson(collector, "/api/loans/" + b + "/contacts", Map.of("channel", "CALL")));
		assertThat(blocked.get("result").asString()).isEqualTo("BLOCKED");
		assertThat(blocked.get("reason").asString()).contains("R45");
		// 법정 통지는 허용
		assertThat(ok(postJson(collector, "/api/loans/" + b + "/contacts", Map.of("channel", "STATUTORY_NOTICE")))
				.get("result").asString()).isEqualTo("ALLOWED");

		// 해제 (기각) → 다시 연락 가능, 그날부터 이자
		businessDate("2026-09-10");
		long stopId = ok(getJson(head, "/api/loans/" + b + "/collection-stops")).get(0).get("id").asLong();
		JsonNode released = ok(postJson(lead, "/api/loans/" + b + "/collection-stops/" + stopId + "/release",
				Map.of("reason", "회생 신청 기각")));
		assertThat(released).hasSize(2);
		assertThat(released.get(0).get("status").asString()).isEqualTo("RELEASED");
		assertThat(ok(postJson(collector, "/api/loans/" + b + "/contacts", Map.of("channel", "CALL"))).get("result")
				.asString()).isEqualTo("ALLOWED");
	}

	@Test
	void 공적_채무조정_확정_시_조정_금리를_금리_이력에_추가() throws Exception {
		businessDate("2026-09-01");
		MockHttpSession head = login("HEAD");
		MockHttpSession lead = login("COLLECTION_LEAD");
		long staff = staffId(newStaff("COLLECTOR", 2, false));
		long a = openLoan(head, customer(head, "공적조정"), "P1", 5_000_000, "19.9", 12, 0, 10, "2026-07-01", staff);
		JsonNode rows = ok(postJson(lead, "/api/loans/" + a + "/collection-stops",
				Map.of("reason", "PUBLIC_WORKOUT", "effectiveDate", "2026-08-25", "memo", "신청 접수 통지 (가상)")));
		long stopId = rows.get(0).get("id").asLong();
		assertThat(rows.get(0).get("interestEventId").isNull()).isTrue();

		// 현재 이율보다 높은 조정 금리는 거절
		var tooHigh = postJson(lead, "/api/loans/" + a + "/collection-stops/" + stopId + "/confirm-relief",
				Map.of("rate", "20.0", "validFrom", "2026-09-01")).andReturn().getResponse();
		assertThat(tooHigh.getStatus()).isEqualTo(422);

		JsonNode confirmed = ok(postJson(lead, "/api/loans/" + a + "/collection-stops/" + stopId + "/confirm-relief",
				Map.of("rate", "10.0", "validFrom", "2026-09-01")));
		assertThat(confirmed.get("reliefRate").asDouble()).isEqualTo(10.0);
		JsonNode rates = ok(getJson(head, "/api/loans/" + a + "/history")).get("rateTerms");
		assertThat(rates.get(rates.size() - 1).get("rate").asDouble()).isEqualTo(10.0);
		assertThat(rates.get(rates.size() - 1).get("validFrom").asString()).isEqualTo("2026-09-01");
		assertThat(detail(head, a).get("currentRate").asDouble()).isEqualTo(10.0);
	}
}
