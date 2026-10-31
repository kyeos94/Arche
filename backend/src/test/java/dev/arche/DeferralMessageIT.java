package dev.arche;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arche.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

/**
 * 연락 유예(최대 3개월, 1회 연장)와 자동 문자(약정일 3일 전 납입 안내, 연체 1·7·30일째, 완제 안내) — 정책서 6장.
 */
class DeferralMessageIT extends IntegrationTest {

	@Test
	void 연락_유예는_최대_3개월_1회_연장이고_유예_중에는_추심_연락을_막는다() throws Exception {
		businessDate("2026-06-12");
		MockHttpSession head = login("HEAD");
		String collectorId = newStaff("COLLECTOR", 2, false);
		MockHttpSession collector = loginAs(collectorId);
		long account = openLoan(head, customer(head, "유예"), "P1", 10_000_000, "19.9", 12, 0, 10, "2026-01-10",
				staffId(collectorId));
		String base = "/api/loans/" + account + "/deferrals";

		assertThat(postJson(collector, base, Map.of("startDate", "2026-06-12", "endDate", "2026-09-13", "reason", "입원"))
				.andReturn().getResponse().getStatus()).isEqualTo(422);
		long id = ok(postJson(collector, base,
				Map.of("startDate", "2026-06-12", "endDate", "2026-09-12", "reason", "입원"))).get("id").asLong();

		JsonNode blocked = ok(postJson(collector, "/api/loans/" + account + "/contacts", Map.of("channel", "CALL")));
		assertThat(blocked.get("result").asString()).isEqualTo("BLOCKED");
		assertThat(blocked.get("reason").asString()).contains("연락 유예");
		assertThat(ok(postJson(collector, "/api/loans/" + account + "/contacts",
				Map.of("channel", "STATUTORY_NOTICE"))).get("result").asString()).isEqualTo("ALLOWED");

		ok(postJson(collector, base + "/" + id + "/extend", Map.of("endDate", "2026-12-12")));
		assertThat(postJson(collector, base + "/" + id + "/extend", Map.of("endDate", "2026-12-20")).andReturn()
				.getResponse().getStatus()).isEqualTo(422);

		ok(postJson(collector, base + "/" + id + "/end", Map.of()));
		assertThat(ok(postJson(collector, "/api/loans/" + account + "/contacts", Map.of("channel", "CALL")))
				.get("result").asString()).isEqualTo("ALLOWED");
	}

	@Test
	void 자동_문자_납입_안내_연체_1일째_유예_중_7일째_건너뜀_완제_안내() throws Exception {
		businessDate("2026-07-03");
		MockHttpSession head = login("HEAD");
		MockHttpSession teller = login("TELLER");
		MockHttpSession admin = login("SYSTEM_ADMIN");
		// 실행 06-05, 약정일 10일 → 1회차 07-10
		long loan = openLoan(head, customer(head, "문자"), "P1", 10_000_000, "19.9", 12, 0, 10, "2026-06-05", null);
		long revolving = openLoan(head, customer(head, "완제"), "P5", 3_000_000, "19.5", 12, 0, 20, "2026-07-01",
				null);

		// 07-06 마감 → 07-07(약정일 3일 전) 납입 안내
		businessDate("2026-07-06");
		ok(postJson(admin, "/api/operation/closings", Map.of()));
		ok(postJson(admin, "/api/collection/messages/dispatch", Map.of()));
		assertThat(messages(head, loan)).anySatisfy(m -> {
			assertThat(m.get("type").asString()).isEqualTo("PAYMENT_REMINDER");
			assertThat(m.get("status").asString()).isEqualTo("SENT");
			assertThat(m.get("scheduledDate").asString()).isEqualTo("2026-07-07");
		});

		// 1회차 미납: 07-10 마감 → 07-11이 연체 1일째
		businessDate("2026-07-10");
		ok(postJson(admin, "/api/operation/closings", Map.of()));
		ok(postJson(admin, "/api/collection/messages/dispatch", Map.of()));
		assertThat(messages(head, loan)).anySatisfy(m -> {
			assertThat(m.get("type").asString()).isEqualTo("DELINQUENCY_1");
			assertThat(m.get("status").asString()).isEqualTo("SENT");
		});
		// 연체 안내는 추심 연락이라 7일 7회 기록에 남는다
		JsonNode contacts = ok(getJson(head, "/api/loans/" + loan + "/contacts"));
		assertThat(contacts.get("contacts").get(0).get("channel").asString()).isEqualTo("SMS");

		// 07-16: 연락 유예 등록, 자유상환 계약은 완제
		businessDate("2026-07-16");
		ok(postJson(head, "/api/loans/" + loan + "/deferrals",
				Map.of("startDate", "2026-07-16", "endDate", "2026-08-16", "reason", "재난")));
		long payoff = ok(getJson(head, "/api/loans/" + revolving + "/quote")).get("total").asLong();
		pay(teller, revolving, "2026-07-16", payoff, tx());
		assertThat(detail(head, revolving).get("status").asString()).isEqualTo("PAID_OFF");

		// 07-16 마감 → 07-17: 연체 7일째지만 유예 중이라 건너뜀, 완제 안내는 발송
		ok(postJson(admin, "/api/operation/closings", Map.of()));
		JsonNode report = ok(postJson(admin, "/api/collection/messages/dispatch", Map.of()));
		assertThat(report.get("skipped").asInt()).isPositive();
		assertThat(messages(head, loan)).anySatisfy(m -> {
			assertThat(m.get("type").asString()).isEqualTo("DELINQUENCY_7");
			assertThat(m.get("status").asString()).isEqualTo("SKIPPED");
			assertThat(m.get("skipReason").asString()).contains("연락 유예");
		});
		assertThat(messages(head, revolving)).anySatisfy(m -> {
			assertThat(m.get("type").asString()).isEqualTo("PAYOFF");
			assertThat(m.get("status").asString()).isEqualTo("SENT");
		});
	}

	private JsonNode messages(MockHttpSession session, long accountId) throws Exception {
		return ok(getJson(session, "/api/loans/" + accountId + "/messages"));
	}
}
