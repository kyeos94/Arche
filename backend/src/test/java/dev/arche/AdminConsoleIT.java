package dev.arche;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import dev.arche.support.IntegrationTest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

/** 메뉴 관리(R70)·권한 관리(R69) */
class AdminConsoleIT extends IntegrationTest {

	@Test
	void 권한을_빼면_로그인_중인_세션도_바로_막히고_기본값으로_되돌릴_수_있다() throws Exception {
		MockHttpSession admin = login("SYSTEM_ADMIN");
		MockHttpSession teller = login("TELLER");
		assertThat(getJson(teller, "/api/loans").andReturn().getResponse().getStatus()).isEqualTo(200);
		try {
			JsonNode matrix = ok(postJson(admin, "/api/admin/permissions",
					Map.of("role", "TELLER", "permission", "loan.read", "granted", false, "reason", "출납 조회 제한 시험")));
			assertThat(matrix.get("changedCells").asInt()).isEqualTo(1);
			assertThat(cell(matrix, "loan.read", "TELLER").get("granted").asBoolean()).isFalse();
			assertThat(cell(matrix, "loan.read", "TELLER").get("byDefault").asBoolean()).isTrue();

			// 같은 세션의 다음 요청부터 반영
			assertThat(getJson(teller, "/api/loans").andReturn().getResponse().getStatus()).isEqualTo(403);
			assertThat(ok(getJson(teller, "/api/auth/me")).get("permissions").toString()).doesNotContain("loan.read");

			JsonNode changes = ok(getJson(admin, "/api/admin/permissions/changes"));
			assertThat(changes.get(0).get("action").asString()).isEqualTo("REVOKE");
			assertThat(changes.get(0).get("reason").asString()).isEqualTo("출납 조회 제한 시험");
		} finally {
			ok(postJson(admin, "/api/admin/permissions/reset", Map.of("reason", "시험 후 원복")));
		}
		assertThat(getJson(teller, "/api/loans").andReturn().getResponse().getStatus()).isEqualTo(200);
		assertThat(ok(getJson(admin, "/api/admin/permissions")).get("changedCells").asInt()).isZero();
	}

	@Test
	void 잠긴_칸과_사유_없는_변경은_거절하고_관리자가_아니면_403() throws Exception {
		MockHttpSession admin = login("SYSTEM_ADMIN");
		assertThat(postJson(admin, "/api/admin/permissions",
				Map.of("role", "SYSTEM_ADMIN", "permission", "admin.manage", "granted", false, "reason", "잠금 시험"))
				.andReturn().getResponse().getStatus()).isEqualTo(422);
		assertThat(postJson(admin, "/api/admin/permissions",
				Map.of("role", "HEAD", "permission", "report.read", "granted", false, "reason", ""))
				.andReturn().getResponse().getStatus()).isEqualTo(422);
		MockHttpSession head = login("HEAD");
		assertThat(getJson(head, "/api/admin/permissions").andReturn().getResponse().getStatus()).isEqualTo(403);
		assertThat(putMenu(head, ok(getJson(head, "/api/menus")), "권한 시험")).isEqualTo(403);
	}

	@Test
	void 메뉴를_바꾸고_필수_화면은_뺄_수_없다() throws Exception {
		MockHttpSession admin = login("SYSTEM_ADMIN");
		JsonNode original = ok(getJson(admin, "/api/menus"));
		assertThat(original.get(0).get("label").asString()).isEqualTo("대출신청관리");
		try {
			// 대분류 하나 추가 + 보고서 대분류의 메뉴를 그쪽으로 옮김
			List<Map<String, Object>> groups = groups(original);
			Map<String, Object> reports = groups.stream().filter(g -> g.get("label").equals("보고서")).findFirst()
					.orElseThrow();
			Map<String, Object> added = new LinkedHashMap<>();
			added.put("label", "경영정보");
			added.put("items", reports.get("items"));
			groups.remove(reports);
			groups.add(added);
			assertThat(send(admin, groups, "보고서 대분류 이름 변경")).isEqualTo(200);
			JsonNode changed = ok(getJson(login("TELLER"), "/api/menus"));
			assertThat(changed.get(changed.size() - 1).get("label").asString()).isEqualTo("경영정보");

			// 메뉴 관리 화면을 빼려 하면 거절
			List<Map<String, Object>> broken = groups(changed);
			for (Map<String, Object> g : broken) {
				@SuppressWarnings("unchecked")
				List<Map<String, Object>> items = (List<Map<String, Object>>) g.get("items");
				items.removeIf(i -> i.get("path").equals("/admin/menus"));
			}
			assertThat(send(admin, broken, "필수 화면 삭제 시험")).isEqualTo(422);
		} finally {
			assertThat(putMenu(admin, original, "시험 후 원복")).isEqualTo(200);
		}
	}

	private static JsonNode cell(JsonNode matrix, String permission, String role) {
		for (JsonNode row : matrix.get("permissions")) {
			if (row.get("code").asString().equals(permission)) {
				for (JsonNode c : row.get("cells")) {
					if (c.get("role").asString().equals(role)) {
						return c;
					}
				}
			}
		}
		throw new AssertionError(permission + "/" + role);
	}

	private List<Map<String, Object>> groups(JsonNode menu) {
		List<Map<String, Object>> groups = new ArrayList<>();
		for (JsonNode g : menu) {
			Map<String, Object> group = new LinkedHashMap<>();
			group.put("label", g.get("label").asString());
			List<Map<String, Object>> items = new ArrayList<>();
			for (JsonNode i : g.get("items")) {
				items.add(new LinkedHashMap<>(Map.of("path", i.get("path").asString(), "label",
						i.get("label").asString())));
			}
			group.put("items", items);
			groups.add(group);
		}
		return groups;
	}

	private int putMenu(MockHttpSession session, JsonNode menu, String reason) throws Exception {
		return send(session, groups(menu), reason);
	}

	private int send(MockHttpSession session, List<Map<String, Object>> groups, String reason) throws Exception {
		return mvc.perform(put("/api/admin/menus").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("groups", groups, "reason", reason)))).andReturn().getResponse()
				.getStatus();
	}
}
