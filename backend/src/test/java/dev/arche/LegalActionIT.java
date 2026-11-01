package dev.arche;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arche.support.IntegrationTest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

/**
 * R51 법조치 단계와 소멸시효 연결, R52 법조치 비용(COST)과 배당금 입금.
 */
class LegalActionIT extends IntegrationTest {

	@Test
	void 지급명령_신청은_시효를_중단하고_확정은_10년으로_연장하며_비용은_COST로_적재한다() throws Exception {
		businessDate("2026-08-13");
		MockHttpSession head = login("HEAD");
		long account = acquire(head, "지급명령", "2026-08-13", "2026-08-13");

		businessDate("2027-03-02");
		MockHttpSession teller = login("TELLER");
		var request = new HashMap<String, Object>(Map.of("type", "PAYMENT_ORDER", "filedOn", "2027-03-02",
				"courtName", "가상지방법원", "caseRef", "자유입력-1", "claimAmount", 5_350_000));
		// R37: 채권팀장·본부장만
		assertThat(postJson(teller, "/api/loans/" + account + "/legal-actions", request).andReturn().getResponse()
				.getStatus()).isEqualTo(403);
		JsonNode action = ok(postJson(head, "/api/loans/" + account + "/legal-actions", request));
		long actionId = action.get("id").asLong();
		assertThat(action.get("status").asString()).isEqualTo("FILED");
		assertThat(action.get("stages")).hasSize(1);
		assertThat(action.get("stages").get(0).get("limitationEventId").isNull()).isFalse();
		assertThat(limitation(head, account)).isEqualTo("2032-03-02");

		businessDate("2027-04-01");
		ok(postJson(head, "/api/legal-actions/" + actionId + "/stages", Map.of("stage", "ISSUED", "date", "2027-03-10")));
		// 앞 단계보다 이른 날짜, 되돌아가는 단계는 불가
		assertThat(postJson(head, "/api/legal-actions/" + actionId + "/stages",
				Map.of("stage", "SERVED", "date", "2027-03-05")).andReturn().getResponse().getStatus()).isEqualTo(422);
		ok(postJson(head, "/api/legal-actions/" + actionId + "/stages", Map.of("stage", "SERVED", "date", "2027-03-20")));
		assertThat(postJson(head, "/api/legal-actions/" + actionId + "/stages",
				Map.of("stage", "ISSUED", "date", "2027-03-25")).andReturn().getResponse().getStatus()).isEqualTo(422);
		JsonNode confirmed = ok(postJson(head, "/api/legal-actions/" + actionId + "/stages",
				Map.of("stage", "CONFIRMED", "date", "2027-04-01", "memo", "이의 없음")));
		assertThat(confirmed.get("status").asString()).isEqualTo("CONFIRMED");
		assertThat(limitation(head, account)).isEqualTo("2037-04-01");
		// 확정 후에는 취하 불가
		assertThat(postJson(head, "/api/legal-actions/" + actionId + "/stages",
				Map.of("stage", "WITHDRAWN", "date", "2027-04-01")).andReturn().getResponse().getStatus()).isEqualTo(422);

		long costBefore = detail(head, account).get("costBalance").asLong();
		JsonNode charged = ok(postJson(head, "/api/legal-actions/" + actionId + "/costs",
				Map.of("costType", "COURT_FEE", "amount", 50_000, "incurredOn", "2027-03-02", "memo", "인지대")));
		assertThat(charged.get("totalCost").asLong()).isEqualTo(50_000);
		assertThat(detail(head, account).get("costBalance").asLong()).isEqualTo(costBefore + 50_000);
		assertThat(ok(getJson(head, "/api/loans/" + account + "/legal-actions"))).hasSize(1);
	}

	@Test
	void 압류_취하는_중단을_무효로_하고_배당금은_비용부터_충당하되_시효를_중단하지_않는다() throws Exception {
		businessDate("2026-08-13");
		MockHttpSession head = login("HEAD");
		long account = acquire(head, "경매", "2026-08-13", "2026-08-13");

		businessDate("2027-03-02");
		long seizure = ok(postJson(head, "/api/loans/" + account + "/legal-actions",
				Map.of("type", "SEIZURE", "filedOn", "2027-03-02", "targetDesc", "가상 예금채권"))).get("id").asLong();
		assertThat(limitation(head, account)).isEqualTo("2032-03-02");
		JsonNode withdrawn = ok(postJson(head, "/api/legal-actions/" + seizure + "/stages",
				Map.of("stage", "WITHDRAWN", "date", "2027-03-02")));
		assertThat(withdrawn.get("closedOn").asString()).isEqualTo("2027-03-02");
		assertThat(withdrawn.get("nextStages")).isEmpty();
		assertThat(limitation(head, account)).isEqualTo("2031-08-13");

		long auction = ok(postJson(head, "/api/loans/" + account + "/legal-actions",
				Map.of("type", "AUCTION", "filedOn", "2027-03-02", "targetDesc", "가상 부동산"))).get("id").asLong();
		ok(postJson(head, "/api/legal-actions/" + auction + "/stages", Map.of("stage", "DISTRIBUTED", "date", "2027-03-02")));
		// 경매 신청 = 압류 효력으로 중단
		assertThat(limitation(head, account)).isEqualTo("2032-03-02");

		MockHttpSession teller = login("TELLER");
		long costBefore = detail(head, account).get("costBalance").asLong();
		assertThat(costBefore).isEqualTo(150_000);
		JsonNode payment = ok(postJson(teller, "/api/payments", Map.of("legalActionId", auction, "bankDate",
				"2027-03-02", "amount", 100_000, "channel", "AUCTION_DISTRIBUTION", "depositorName", "가상법원")));
		assertThat(payment.get("accountId").asLong()).isEqualTo(account);
		assertThat(payment.get("channel").asString()).isEqualTo("AUCTION_DISTRIBUTION");
		// N1 충당 순서: 비용 먼저 (R52)
		assertThat(detail(head, account).get("costBalance").asLong()).isEqualTo(50_000);
		// 배당금은 채무자의 승인이 아니다 (R50): 경매 신청일 기준 그대로
		assertThat(limitation(head, account)).isEqualTo("2032-03-02");

		// 배당금이 아닌 채널에는 법조치를 연결하지 않는다
		assertThat(postJson(teller, "/api/payments", Map.of("legalActionId", auction, "bankDate", "2027-03-02",
				"amount", 1_000, "channel", "BANK_TRANSFER")).andReturn().getResponse().getStatus()).isEqualTo(422);
	}

	private String limitation(MockHttpSession session, long account) throws Exception {
		return ok(getJson(session, "/api/loans/" + account + "/limitation")).get("completionDate").asString();
	}

	private long acquire(MockHttpSession head, String name, String acquiredOn, String limitationStart)
			throws Exception {
		var body = new HashMap<String, Object>();
		body.put("customerId", customer(head, name));
		body.put("acquiredOn", acquiredOn);
		body.put("acquisitionPrice", 800_000);
		body.put("seller", "가상저축은행(가상)");
		body.put("originalContractDate", "2020-01-01");
		body.put("originalRate", "16.0");
		body.put("originalLateRate", "19.0");
		body.put("principal", 4_000_000);
		body.put("preAcqInterest", 1_200_000);
		body.put("cost", 150_000);
		body.put("interestCharged", true);
		body.put("limitationStartDate", limitationStart);
		return ok(postJson(head, "/api/loans/acquisitions", body)).get("account").get("id").asLong();
	}
}
