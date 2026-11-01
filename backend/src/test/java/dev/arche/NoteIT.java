package dev.arche;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arche.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

/** 쪽지 (R74) */
class NoteIT extends IntegrationTest {

	@Test
	void 보내고_받고_읽음_표시와_숨기기() throws Exception {
		String aId = newStaff("COLLECTOR", 2, false);
		String bId = newStaff("COLLECTOR", 2, false);
		MockHttpSession a = loginAs(aId);
		MockHttpSession b = loginAs(bId);
		MockHttpSession outsider = login("TELLER");
		long bStaff = staffId(bId);

		long id = ok(postJson(a, "/api/notes", Map.of("staffIds", List.of(bStaff), "teamIds", List.of(), "subject",
				"상환 문의", "body", "L00001007 고객이 상환 문의했습니다", "ref",
				Map.of("type", "LOAN", "id", 1, "label", "L00001007")))).get("id").asLong();

		assertThat(ok(getJson(b, "/api/notes/unread-count")).get("count").asInt()).isEqualTo(1);
		JsonNode inbox = ok(getJson(b, "/api/notes/inbox"));
		assertThat(inbox.get(0).get("subject").asString()).isEqualTo("상환 문의");
		assertThat(inbox.get(0).get("sender").asString()).isEqualTo("테스트 COLLECTOR");
		assertThat(inbox.get(0).get("readAt").isNull()).isTrue();

		// 보낸 사람·받은 사람 말고는 열 수 없다
		assertThat(postJson(outsider, "/api/notes/" + id + "/open", Map.of()).andReturn().getResponse().getStatus())
				.isEqualTo(422);

		JsonNode opened = ok(postJson(b, "/api/notes/" + id + "/open", Map.of()));
		assertThat(opened.get("body").asString()).contains("상환 문의");
		assertThat(opened.get("ref").get("label").asString()).isEqualTo("L00001007");
		assertThat(opened.get("recipients").isNull()).isTrue(); // 받은 사람은 다른 사람의 읽음 여부를 못 봄
		assertThat(ok(getJson(b, "/api/notes/unread-count")).get("count").asInt()).isZero();

		JsonNode sent = ok(getJson(a, "/api/notes/sent"));
		assertThat(sent.get(0).get("readCount").asInt()).isEqualTo(1);
		JsonNode mine = ok(postJson(a, "/api/notes/" + id + "/open", Map.of()));
		assertThat(mine.get("recipients").get(0).get("readAt").isNull()).isFalse();

		assertThat(postJson(b, "/api/notes/" + id + "/hide", Map.of()).andReturn().getResponse().getStatus())
				.isEqualTo(200);
		assertThat(ok(getJson(b, "/api/notes/inbox")).size()).isZero();
		// 받은 사람이 숨겨도 보낸 쪽지함에는 남는다
		assertThat(ok(getJson(a, "/api/notes/sent")).get(0).get("id").asLong()).isEqualTo(id);
	}

	@Test
	void 팀에게_보내면_나를_뺀_팀원_전원이_받고_주민번호는_거절() throws Exception {
		String me = newStaff("COLLECTION_LEAD", 3, false);
		String m1 = newStaff("COLLECTOR", 3, false);
		String m2 = newStaff("COLLECTOR", 3, false);
		MockHttpSession lead = loginAs(me);
		ok(postJson(lead, "/api/notes",
				Map.of("staffIds", List.of(), "teamIds", List.of(3), "subject", "주간 회의", "body", "금요일 10시")));
		for (String member : List.of(m1, m2)) {
			assertThat(ok(getJson(loginAs(member), "/api/notes/inbox")).get(0).get("subject").asString())
					.isEqualTo("주간 회의");
		}
		assertThat(ok(getJson(lead, "/api/notes/inbox")).size()).isZero();

		var r = postJson(lead, "/api/notes", Map.of("staffIds", List.of(staffId(m1)), "teamIds", List.of(), "subject",
				"확인", "body", "고객 번호 900101-1234567 확인 부탁")).andReturn().getResponse();
		assertThat(r.getStatus()).isEqualTo(422);
		assertThat(r.getContentAsString()).contains("NOTE_SENSITIVE");
	}

	@Test
	void 결재_요청과_결과는_시스템_쪽지로_알린다() throws Exception {
		businessDate("2026-07-01");
		MockHttpSession head = login("HEAD");
		MockHttpSession approver = login("HEAD");
		String leadId = newStaff("COLLECTION_LEAD", 2, false);
		MockHttpSession lead = loginAs(leadId);
		long collector = staffId(newStaff("COLLECTOR", 2, false));
		long account = openLoan(head, customer(head, "쪽지"), "P1", 5_000_000, "19.9", 12, 0, 10, "2026-07-01",
				collector);
		long request = ok(postJson(lead, "/api/adjustment-requests", Map.of("type", "ADJUSTMENT", "accountId", account,
				"costDelta", 10_000, "reason", "송달료 (법정 청구 가능 비용 확인)"))).get("id").asLong();

		JsonNode note = await(approver, "[결재 요청]");
		assertThat(note.get("sender").isNull()).isTrue(); // 시스템
		assertThat(note.get("ref").get("id").asLong()).isEqualTo(account);

		ok(postJson(approver, "/api/adjustment-requests/" + request + "/decision", Map.of("approve", false)));
		assertThat(await(lead, "[결재 반려]").get("subject").asString()).contains("수기 조정");
	}

	/** 시스템 쪽지는 커밋 뒤 비동기로 가므로 잠시 기다린다 */
	private JsonNode await(MockHttpSession session, String subjectPrefix) throws Exception {
		for (int i = 0; i < 50; i++) {
			for (JsonNode n : ok(getJson(session, "/api/notes/inbox"))) {
				if (n.get("subject").asString().startsWith(subjectPrefix)) {
					return n;
				}
			}
			Thread.sleep(100);
		}
		throw new AssertionError(subjectPrefix + " 쪽지가 오지 않았습니다");
	}
}
