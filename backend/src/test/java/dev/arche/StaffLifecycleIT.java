package dev.arche;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arche.support.IntegrationTest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

/** 직원 등록·퇴사(담당 건 이관)·복직 (R75) */
class StaffLifecycleIT extends IntegrationTest {

	@Test
	void 등록하면_임시_비밀번호로_로그인하고_첫_로그인에_비밀번호를_바꿔야_한다() throws Exception {
		MockHttpSession admin = login("SYSTEM_ADMIN");
		String loginId = "new" + System.nanoTime() % 1_000_000;
		JsonNode created = ok(postJson(admin, "/api/admin/staff",
				Map.of("loginId", loginId, "name", "신입 채권", "role", "COLLECTOR", "teamId", 2)));
		String temp = created.get("temporaryPassword").asString();
		assertThat(temp).hasSize(12);

		var r = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/login")
				.with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("loginId", loginId, "password", temp)))).andReturn();
		assertThat(r.getResponse().getStatus()).isEqualTo(200);
		assertThat(r.getResponse().getContentAsString()).contains("passwordChangeRequired\":true");

		// 같은 ID, 데모 역할, 팀 없음은 거절
		assertThat(status(admin, "/api/admin/staff",
				Map.of("loginId", loginId, "name", "중복", "role", "TELLER", "teamId", 4))).isEqualTo(422);
		assertThat(status(admin, "/api/admin/staff",
				Map.of("loginId", loginId + "x", "name", "데모", "role", "DEMO_ALL", "teamId", 9))).isEqualTo(422);
		assertThat(status(login("HEAD"), "/api/admin/staff",
				Map.of("loginId", loginId + "y", "name", "권한", "role", "TELLER", "teamId", 4))).isEqualTo(403);
	}

	@Test
	void 퇴사하면_담당_계약을_후임에게_넘기고_로그인이_막히며_복직하면_새_임시_비밀번호를_준다() throws Exception {
		businessDate("2026-07-01");
		MockHttpSession admin = login("SYSTEM_ADMIN");
		MockHttpSession head = login("HEAD");
		String leavingId = newStaff("COLLECTOR", 2, false);
		long leaving = staffId(leavingId);
		long successor = staffId(newStaff("COLLECTOR", 2, false));
		long teller = staffId(newStaff("TELLER", 4, false));
		long a = openLoan(head, customer(head, "퇴사이관"), "P1", 3_000_000, "19.9", 12, 0, 10, "2026-07-01", leaving);
		MockHttpSession leavingSession = loginAs(leavingId);

		JsonNode workload = ok(getJson(admin, "/api/admin/staff/" + leaving + "/workload"));
		assertThat(workload.get("loans").asInt()).isEqualTo(1);
		assertThat(workload.get("loanSuccessors").toString()).contains("\"id\":" + successor);

		// 후임 없이, 출납에게는 넘길 수 없다
		var body = new HashMap<String, Object>();
		body.put("reason", "개인 사정으로 퇴사");
		assertThat(status(admin, "/api/admin/staff/" + leaving + "/resignation", body)).isEqualTo(422);
		body.put("loanSuccessorId", teller);
		assertThat(status(admin, "/api/admin/staff/" + leaving + "/resignation", body)).isEqualTo(422);
		assertThat(detail(head, a).get("assignedStaffId").asLong()).isEqualTo(leaving); // 실패하면 아무것도 안 바뀜

		body.put("loanSuccessorId", successor);
		JsonNode result = ok(postJson(admin, "/api/admin/staff/" + leaving + "/resignation", body));
		assertThat(result.get("handover").asString()).contains("계약 1건");
		assertThat(detail(head, a).get("assignedStaffId").asLong()).isEqualTo(successor);

		// 세션이 끊기고 다시 로그인할 수 없다. 받는 사람 목록에서도 빠진다
		assertThat(getJson(leavingSession, "/api/auth/me").andReturn().getResponse().getStatus()).isEqualTo(401);
		assertThat(ok(getJson(admin, "/api/notes/directory")).get("staff").toString())
				.doesNotContain("\"id\":" + leaving + ",");
		JsonNode row = find(ok(getJson(admin, "/api/admin/staff")), leaving);
		assertThat(row.get("enabled").asBoolean()).isFalse();
		assertThat(row.get("resignedAt").isNull()).isFalse();

		JsonNode back = ok(postJson(admin, "/api/admin/staff/" + leaving + "/reinstatement",
				Map.of("reason", "복직 발령")));
		assertThat(back.get("temporaryPassword").asString()).hasSize(12);
		assertThat(find(ok(getJson(admin, "/api/admin/staff")), leaving).get("enabled").asBoolean()).isTrue();

		JsonNode changes = ok(getJson(admin, "/api/admin/staff/status-changes"));
		assertThat(changes.get(0).get("action").asString()).isEqualTo("REINSTATE");
		assertThat(changes.get(1).get("action").asString()).isEqualTo("RESIGN");
		assertThat(changes.get(1).get("detail").asString()).contains("계약 1건");
	}

	@Test
	void 자기_자신은_퇴사_처리할_수_없다() throws Exception {
		String adminId = newStaff("SYSTEM_ADMIN", 9, false);
		MockHttpSession admin = loginAs(adminId);
		assertThat(status(admin, "/api/admin/staff/" + staffId(adminId) + "/resignation", Map.of("reason", "자기 퇴사")))
				.isEqualTo(422);
	}

	private static JsonNode find(JsonNode rows, long id) {
		for (JsonNode r : rows) {
			if (r.get("id").asLong() == id) {
				return r;
			}
		}
		throw new AssertionError("직원 " + id + " 없음");
	}

	private int status(MockHttpSession session, String url, Map<String, Object> body) throws Exception {
		return postJson(session, url, body).andReturn().getResponse().getStatus();
	}
}
