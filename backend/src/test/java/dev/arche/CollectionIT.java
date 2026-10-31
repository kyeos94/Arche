package dev.arche;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arche.support.IntegrationTest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

/**
 * 추심: G14 연락 7일 7회, G19 분할납부 약속 판정·해제, G13 소멸시효 — 저장과 API까지.
 */
class CollectionIT extends IntegrationTest {

	@Test
	void G14_추심_연락_7일_7회를_저장된_기록으로_판정한다() throws Exception {
		businessDate("2026-06-01");
		MockHttpSession head = login("HEAD");
		String collectorId = newStaff("COLLECTOR", 2, false);
		MockHttpSession collector = loginAs(collectorId);
		long account = openLoan(head, customer(head, "연락"), "P1", 10_000_000, "19.9", 12, 0, 10, "2026-01-10",
				staffId(collectorId));

		record Row(String date, String channel, String result, int count) {
		}
		List<Row> rows = List.of(
				new Row("2026-06-01", "CALL", "ALLOWED", 0),
				new Row("2026-06-02", "SMS", "ALLOWED", 1),
				new Row("2026-06-02", "CALL", "ALLOWED", 2),
				new Row("2026-06-04", "VISIT", "ALLOWED", 3),
				new Row("2026-06-05", "SMS", "ALLOWED", 4),
				new Row("2026-06-06", "CALL", "ALLOWED", 5),
				new Row("2026-06-07", "SMS", "ALLOWED", 6),
				new Row("2026-06-07", "CALL", "BLOCKED", 7),
				new Row("2026-06-08", "CALL", "ALLOWED", 6),
				new Row("2026-06-08", "STATUTORY_NOTICE", "ALLOWED", 0),
				new Row("2026-06-08", "SMS", "BLOCKED", 7),
				new Row("2026-06-09", "SMS", "ALLOWED", 5));
		for (int i = 0; i < rows.size(); i++) {
			Row r = rows.get(i);
			businessDate(r.date());
			JsonNode d = ok(postJson(collector, "/api/loans/" + account + "/contacts",
					Map.of("channel", r.channel(), "memo", "#" + (i + 1))));
			assertThat(d.get("result").asString()).as("#" + (i + 1)).isEqualTo(r.result());
			assertThat(d.get("countInWindow").asInt()).as("#" + (i + 1)).isEqualTo(r.count());
		}
		JsonNode list = ok(getJson(collector, "/api/loans/" + account + "/contacts"));
		assertThat(list.get("contacts")).hasSize(12);
		assertThat(list.get("remainingToday").asInt()).isEqualTo(1);
	}

	@Test
	void G19_분할납부_약속_판정과_3회_연속_불이행_해제() throws Exception {
		businessDate("2026-08-01");
		MockHttpSession head = login("HEAD");
		MockHttpSession teller = login("TELLER");
		long account = acquire(head, "약속", "2026-07-01", "2024-01-01");
		JsonNode promise = ok(postJson(head, "/api/loans/" + account + "/promises",
				Map.of("firstMonth", "2026-08", "day", 7, "amount", 300_000, "months", 5, "memo", "매월 7일")));
		assertThat(promise.get("status").asString()).isEqualTo("ACTIVE");

		for (String[] p : new String[][] { { "2026-08-07", "300000" }, { "2026-09-08", "280000" },
				{ "2026-10-07", "250000" }, { "2026-12-09", "100000" } }) {
			businessDate(p[0]);
			pay(teller, account, p[0], Long.parseLong(p[1]), tx());
		}
		businessDate("2026-12-09");
		JsonNode judged = ok(getJson(head, "/api/loans/" + account + "/promises")).get(0).get("judgments");
		assertThat(judged.get(0).get("fulfilled").asBoolean()).isTrue();
		assertThat(judged.get(1).get("fulfilled").asBoolean()).isTrue();
		assertThat(judged.get(2).get("fulfilled").asBoolean()).isFalse();
		assertThat(judged.get(3).get("consecutiveMisses").asInt()).isEqualTo(2);
		assertThat(judged.get(4).get("action").asString()).isEqualTo("TERMINATE");

		// 일마감에서 해제 확정 + 법조치 검토 대상 + 알림
		MockHttpSession admin = login("SYSTEM_ADMIN");
		ok(postJson(admin, "/api/operation/closings", Map.of()));
		JsonNode after = ok(getJson(head, "/api/loans/" + account + "/promises")).get(0);
		assertThat(after.get("status").asString()).isEqualTo("TERMINATED");
		assertThat(after.get("legalReview").asBoolean()).isTrue();
		long alerts = jdbc.sql("select count(*) from collection_alert where account_id = :a and type in"
				+ " ('PROMISE_MISSED', 'PROMISE_TERMINATED')").param("a", account).query(Long.class).single();
		assertThat(alerts).isEqualTo(3);

		// 원채권 일반 계약에는 약속을 등록할 수 없다
		businessDate("2026-06-12");
		long normal = openLoan(head, customer(head, "일반"), "P1", 10_000_000, "19.9", 12, 0, 10, "2026-01-10",
				null);
		assertThat(postJson(head, "/api/loans/" + normal + "/promises",
				Map.of("firstMonth", "2026-07", "day", 7, "amount", 100_000, "months", 3)).andReturn().getResponse()
				.getStatus()).isEqualTo(422);
	}

	@Test
	void G13_소멸시효_중단과_판결_확정() throws Exception {
		businessDate("2026-08-13");
		MockHttpSession head = login("HEAD");
		MockHttpSession teller = login("TELLER");
		long account = acquire(head, "시효", "2026-08-13", "2026-08-13");
		assertThat(ok(getJson(head, "/api/loans/" + account + "/limitation")).get("completionDate").asString())
				.isEqualTo("2031-08-13");

		businessDate("2027-03-02");
		pay(teller, account, "2027-03-02", 10_000, tx());
		JsonNode interrupted = ok(getJson(head, "/api/loans/" + account + "/limitation"));
		assertThat(interrupted.get("completionDate").asString()).isEqualTo("2032-03-02");

		businessDate("2027-09-02");
		MockHttpSession teller2 = login("TELLER");
		assertThat(postJson(teller2, "/api/loans/" + account + "/limitation-events",
				Map.of("type", "JUDGMENT_CONFIRMED", "date", "2027-09-01")).andReturn().getResponse().getStatus())
				.isEqualTo(403);
		JsonNode judged = ok(postJson(head, "/api/loans/" + account + "/limitation-events",
				Map.of("type", "JUDGMENT_CONFIRMED", "date", "2027-09-01", "memo", "지급명령 확정")));
		assertThat(judged.get("completionDate").asString()).isEqualTo("2037-09-01");
		assertThat(judged.get("alertDates").get(0).asString()).isEqualTo("2037-03-05");
		assertThat(detail(head, account).get("limitationCompletionDate").asString()).isEqualTo("2037-09-01");
	}

	@Test
	void 소멸시효가_완성된_채권은_추심_연락을_차단하고_법정_통지만_허용한다() throws Exception {
		businessDate("2026-06-12");
		MockHttpSession head = login("HEAD");
		long account = acquire(head, "완성", "2026-06-01", "2020-01-01");
		JsonNode blocked = ok(postJson(head, "/api/loans/" + account + "/contacts", Map.of("channel", "CALL")));
		assertThat(blocked.get("result").asString()).isEqualTo("BLOCKED");
		assertThat(blocked.get("reason").asString()).contains("소멸시효");
		JsonNode notice = ok(postJson(head, "/api/loans/" + account + "/contacts",
				Map.of("channel", "STATUTORY_NOTICE")));
		assertThat(notice.get("result").asString()).isEqualTo("ALLOWED");
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
