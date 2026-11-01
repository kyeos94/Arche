package dev.arche;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arche.support.IntegrationTest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

/**
 * 문자·팩스·우편 발송과 연락 타임라인 (R59, guide 13.2): 7일 7회·연락 유예 판정, 법정 통지 예외, 메모, 템플릿 관리.
 */
class CommunicationIT extends IntegrationTest {

	@Test
	void 추심_안내는_7일_7회에_세고_법정_통지는_한도와_무관하며_타임라인에_한_번씩_보인다() throws Exception {
		businessDate("2026-06-12");
		MockHttpSession head = login("HEAD");
		String collectorId = newStaff("COLLECTOR", 2, false);
		MockHttpSession collector = loginAs(collectorId);
		long loan = openLoan(head, customer(head, "타임라인"), "P1", 10_000_000, "19.9", 12, 0, 10, "2026-01-10",
				staffId(collectorId));
		String base = "/api/loans/" + loan;

		JsonNode sms = ok(postJson(collector, base + "/dispatches",
				Map.of("channel", "SMS", "body", "{고객명}님, {계약번호} 납부 안내입니다.")));
		assertThat(sms.get("status").asString()).isEqualTo("SENT");
		assertThat(sms.get("purpose").asString()).isEqualTo("COLLECTION");
		assertThat(sms.get("body").asString()).startsWith("타임라인님, L");
		assertThat(sms.get("remainingToday").asInt()).isEqualTo(6);

		// 우편 추심 안내도 1회로 센다
		long demandTemplate = templateId(collector, "POST_DEMAND");
		JsonNode post = ok(postJson(collector, base + "/dispatches", Map.of("channel", "POST", "templateId",
				demandTemplate)));
		assertThat(post.get("status").asString()).isEqualTo("ACCEPTED");
		assertThat(post.get("providerId").asString()).startsWith("RG");
		assertThat(post.get("remainingToday").asInt()).isEqualTo(5);

		for (int i = 0; i < 5; i++) {
			ok(postJson(collector, base + "/contacts", Map.of("channel", "CALL", "memo", "통화 " + i)));
		}
		JsonNode blocked = ok(postJson(collector, base + "/dispatches", Map.of("channel", "SMS", "body", "한 번 더")));
		assertThat(blocked.get("status").asString()).isEqualTo("BLOCKED");
		assertThat(blocked.get("reason").asString()).contains("7회");

		// 법정·계약상 통지 템플릿은 한도와 무관
		long noticeTemplate = templateId(collector, "POST_ACCEL_NOTICE");
		JsonNode notice = ok(postJson(collector, base + "/dispatches",
				Map.of("channel", "POST", "templateId", noticeTemplate)));
		assertThat(notice.get("status").asString()).isEqualTo("ACCEPTED");
		assertThat(notice.get("purpose").asString()).isEqualTo("NOTICE");

		// 팩스 응답 없음(끝 0000)은 실패로 남는다
		long faxTemplate = templateId(collector, "FAX_BALANCE");
		JsonNode fax = ok(postJson(collector, base + "/dispatches",
				Map.of("channel", "FAX", "templateId", faxTemplate, "faxNumber", "02-555-0000")));
		assertThat(fax.get("status").asString()).isEqualTo("FAILED");
		assertThat(fax.get("recipientMasked").asString()).doesNotContain("555");

		ok(postJson(collector, base + "/memos", Map.of("body", "배우자와 통화, 다음 주 입금 예정")));

		JsonNode timeline = ok(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.get(base + "/timeline").param("page", "0").param("size", "50").session(collector)));
		assertThat(timeline.get("remainingToday").asInt()).isZero();
		List<String> kinds = new ArrayList<>();
		timeline.get("content").forEach(i -> kinds.add(i.get("kind").asString() + ":" + i.get("channel").asString()));
		// 발송 5건(문자 2, 우편 2, 팩스 1) + 전화 5건 + 메모 1건. 발송이 남긴 연락 기록은 중복으로 빠진다
		assertThat(timeline.get("totalElements").asInt()).isEqualTo(11);
		assertThat(kinds).filteredOn(k -> k.startsWith("DISPATCH")).hasSize(5);
		assertThat(kinds).filteredOn(k -> k.equals("CONTACT:CALL")).hasSize(5);
		assertThat(kinds.get(0)).isEqualTo("MEMO:MEMO");

		JsonNode page = ok(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.get(base + "/timeline").param("page", "1").param("size", "4").session(collector)));
		assertThat(page.get("content")).hasSize(4);
		assertThat(page.get("hasNext").asBoolean()).isTrue();

		// 채권담당 범위 밖 직원은 볼 수 없다
		MockHttpSession other = login("COLLECTOR", 3);
		assertThat(getJson(other, base + "/timeline").andReturn().getResponse().getStatus()).isEqualTo(403);
	}

	@Test
	void 연락_유예_중에는_추심_안내를_막고_완제_계약에는_보내지_않는다() throws Exception {
		businessDate("2026-06-12");
		MockHttpSession head = login("HEAD");
		long loan = openLoan(head, customer(head, "유예발송"), "P1", 5_000_000, "19.9", 12, 0, 10, "2026-01-10", null);
		ok(postJson(head, "/api/loans/" + loan + "/deferrals",
				Map.of("startDate", "2026-06-12", "endDate", "2026-07-12", "reason", "입원")));
		JsonNode blocked = ok(postJson(head, "/api/loans/" + loan + "/dispatches",
				Map.of("channel", "SMS", "templateId", templateId(head, "SMS_DUE_GUIDE"))));
		assertThat(blocked.get("status").asString()).isEqualTo("BLOCKED");
		assertThat(blocked.get("reason").asString()).contains("연락 유예");
		JsonNode timeline = ok(getJson(head, "/api/loans/" + loan + "/timeline"));
		assertThat(timeline.get("blockingReason").asString()).contains("연락 유예");

		MockHttpSession teller = login("TELLER");
		long revolving = openLoan(head, customer(head, "완제발송"), "P5", 1_000_000, "19.5", 12, 0, 20, "2026-06-01",
				null);
		long payoff = ok(getJson(head, "/api/loans/" + revolving + "/quote")).get("total").asLong();
		pay(teller, revolving, "2026-06-12", payoff, tx());
		assertThat(postJson(head, "/api/loans/" + revolving + "/dispatches", Map.of("channel", "SMS", "body", "안내"))
				.andReturn().getResponse().getStatus()).isEqualTo(422);
	}

	@Test
	void 템플릿은_운영_관리자만_고치고_모르는_치환자는_거절한다() throws Exception {
		MockHttpSession admin = login("SYSTEM_ADMIN");
		MockHttpSession collector = login("COLLECTOR");
		Map<String, Object> body = new HashMap<>(Map.of("code", "SMS_IT_" + (System.nanoTime() % 100000), "name",
				"테스트", "channel", "SMS", "purpose", "COLLECTION", "body", "{고객이름}님"));
		assertThat(postJson(admin, "/api/message-templates", body).andReturn().getResponse().getStatus())
				.isEqualTo(422);
		body.put("body", "{고객명}님 {기준일}");
		assertThat(postJson(collector, "/api/message-templates", body).andReturn().getResponse().getStatus())
				.isEqualTo(403);
		JsonNode created = ok(postJson(admin, "/api/message-templates", body));
		body.put("active", false);
		JsonNode updated = ok(postJson(admin, "/api/message-templates/" + created.get("id").asLong(), body));
		assertThat(updated.get("active").asBoolean()).isFalse();
		JsonNode active = ok(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.get("/api/message-templates").param("activeOnly", "true").session(collector)));
		active.forEach(t -> assertThat(t.get("id").asLong()).isNotEqualTo(created.get("id").asLong()));
	}

	private long templateId(MockHttpSession session, String code) throws Exception {
		for (JsonNode t : ok(getJson(session, "/api/message-templates"))) {
			if (t.get("code").asString().equals(code)) {
				return t.get("id").asLong();
			}
		}
		throw new AssertionError("템플릿 없음 " + code);
	}
}
