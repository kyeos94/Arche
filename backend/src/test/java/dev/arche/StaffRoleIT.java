package dev.arche;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arche.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

/** 직원 역할·팀 변경 (R71) */
class StaffRoleIT extends IntegrationTest {

	@Test
	void 역할을_바꾸면_세션이_끊기고_다시_로그인하면_새_역할의_권한을_받는다() throws Exception {
		MockHttpSession admin = login("SYSTEM_ADMIN");
		String loginId = newStaff("COLLECTOR", 2, false);
		long id = staffId(loginId);
		MockHttpSession old = loginAs(loginId);
		assertThat(getJson(old, "/api/admin/permissions").andReturn().getResponse().getStatus()).isEqualTo(403);
		assertThat(ok(getJson(old, "/api/auth/me")).get("role").asString()).isEqualTo("COLLECTOR");

		JsonNode changes = ok(postJson(admin, "/api/admin/staff/" + id + "/role",
				Map.of("role", "COLLECTION_LEAD", "teamId", 3, "reason", "채권2팀 팀장 발령")));
		assertThat(changes.get(0).get("toRole").asString()).isEqualTo("채권팀장");
		assertThat(changes.get(0).get("toTeam").asString()).isEqualTo("채권2팀");

		// 예전 역할의 세션은 끊김
		assertThat(getJson(old, "/api/auth/me").andReturn().getResponse().getStatus()).isEqualTo(401);
		MockHttpSession fresh = loginAs(loginId);
		JsonNode me = ok(getJson(fresh, "/api/auth/me"));
		assertThat(me.get("role").asString()).isEqualTo("COLLECTION_LEAD");
		assertThat(me.get("teamId").asLong()).isEqualTo(3);
		assertThat(me.get("permissions").toString()).contains("loan.term.change");

		long logs = jdbc.sql("select count(*) from access_log where action = 'STAFF_ROLE_CHANGED' and target_id = :l")
				.param("l", loginId).query(Long.class).single();
		assertThat(logs).isEqualTo(1);
	}

	@Test
	void 자기_자신_데모_역할_사유_없음은_거절하고_관리자가_아니면_403() throws Exception {
		MockHttpSession admin = login("SYSTEM_ADMIN");
		long me = ok(getJson(admin, "/api/auth/me")).get("id").asLong();
		String target = newStaff("TELLER", 4, false);
		long id = staffId(target);
		assertThat(status(admin, me, Map.of("role", "HEAD", "teamId", 9, "reason", "자기 승격"))).isEqualTo(422);
		assertThat(status(admin, id, Map.of("role", "DEMO_ALL", "teamId", 9, "reason", "데모 부여"))).isEqualTo(422);
		assertThat(status(admin, id, Map.of("role", "HEAD", "teamId", 9, "reason", ""))).isEqualTo(422);
		assertThat(status(admin, id, Map.of("role", "HEAD", "teamId", 999, "reason", "없는 팀"))).isEqualTo(422);
		assertThat(status(login("HEAD"), id, Map.of("role", "HEAD", "teamId", 9, "reason", "권한 시험")))
				.isEqualTo(403);
		JsonNode options = ok(getJson(admin, "/api/admin/staff/role-options"));
		assertThat(options.get("roles").toString()).doesNotContain("DEMO_ALL");
		assertThat(options.get("teams").size()).isGreaterThanOrEqualTo(5);
	}

	private int status(MockHttpSession session, long id, Map<String, Object> body) throws Exception {
		return postJson(session, "/api/admin/staff/" + id + "/role", body).andReturn().getResponse().getStatus();
	}
}
