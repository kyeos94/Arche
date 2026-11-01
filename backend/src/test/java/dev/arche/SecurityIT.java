package dev.arche;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.arche.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

/**
 * 로그인·잠금과 역할별 권한 (정책서 7장, 9.1). 권한표대로 허용·차단되는지 실제 API로 확인한다.
 */
class SecurityIT extends IntegrationTest {

	@Test
	void 로그인하지_않으면_401() throws Exception {
		assertThat(mvc.perform(get("/api/loans")).andReturn().getResponse().getStatus()).isEqualTo(401);
	}

	@Test
	void 다섯_번_연속_실패하면_잠기고_관리자가_해제한다() throws Exception {
		businessDate("2026-06-12");
		String loginId = newStaff("COLLECTOR", 2, false);
		int last = 0;
		for (int i = 0; i < 5; i++) {
			last = mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
					.content(json.writeValueAsString(Map.of("loginId", loginId, "password", "wrong-password"))))
					.andReturn().getResponse().getStatus();
		}
		assertThat(last).isEqualTo(423);
		int correct = mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("loginId", loginId, "password", PASSWORD)))).andReturn()
				.getResponse().getStatus();
		assertThat(correct).isEqualTo(423);

		MockHttpSession admin = login("SYSTEM_ADMIN");
		assertThat(postJson(admin, "/api/admin/staff/" + staffId(loginId) + "/unlock", Map.of()).andReturn()
				.getResponse().getStatus()).isEqualTo(204);
		assertThat(loginAs(loginId)).isNotNull();

		long failures = jdbc.sql("select count(*) from access_log where login_id = :l and action = 'LOGIN_FAILED'")
				.param("l", loginId).query(Long.class).single();
		assertThat(failures).isEqualTo(5);
	}

	@Test
	void 데모_모드가_꺼져_있으면_데모_전용_계정은_로그인할_수_없다() throws Exception {
		String loginId = newStaff("DEMO_ALL", 9, false);
		int status = mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("loginId", loginId, "password", PASSWORD)))).andReturn()
				.getResponse().getStatus();
		assertThat(status).isEqualTo(401);
	}

	@Test
	void 초기_비밀번호는_변경하기_전까지_업무_API를_쓸_수_없다() throws Exception {
		String loginId = newStaff("TELLER", 4, true);
		MockHttpSession s = loginAs(loginId);
		assertThat(getJson(s, "/api/loans").andReturn().getResponse().getStatus()).isEqualTo(403);
		assertThat(ok(getJson(s, "/api/auth/me")).get("passwordChangeRequired").asBoolean()).isTrue();
		assertThat(postJson(s, "/api/auth/password",
				Map.of("currentPassword", PASSWORD, "newPassword", "New-Password-2!")).andReturn().getResponse()
				.getStatus()).isEqualTo(204);
		assertThat(getJson(s, "/api/loans").andReturn().getResponse().getStatus()).isEqualTo(200);
	}

	/** 정책서 9.1 권한표의 대표 행: 역할 | 메서드 | 경로 | 기대 상태 */
	@ParameterizedTest(name = "{0} {1} {2} → {3}")
	@CsvSource(delimiter = '|', textBlock = """
			UNDERWRITER      | GET  | /api/loans                    | 403
			COLLECTOR        | GET  | /api/loans                    | 200
			TELLER           | GET  | /api/loans                    | 200
			SYSTEM_ADMIN     | GET  | /api/loans                    | 200
			SYSTEM_ADMIN     | POST | /api/payments                 | 403
			TELLER           | POST | /api/payments                 | 200
			UNDERWRITER      | POST | /api/payments                 | 403
			TELLER           | POST | /api/loans/{id}/rate-changes  | 403
			COLLECTOR        | POST | /api/loans/{id}/rate-changes  | 403
			COLLECTION_LEAD  | POST | /api/loans/{id}/rate-changes  | 200
			HEAD             | POST | /api/loans/{id}/rate-changes  | 200
			COLLECTOR        | POST | /api/customers/{cid}/reveal   | 403
			TELLER           | POST | /api/customers/{cid}/reveal   | 403
			COLLECTION_LEAD  | POST | /api/customers/{cid}/reveal   | 200
			UNDERWRITING_LEAD| POST | /api/customers/{cid}/reveal   | 200
			HEAD             | POST | /api/operation/closings       | 403
			COLLECTION_LEAD  | GET  | /api/admin/staff              | 403
			SYSTEM_ADMIN     | GET  | /api/admin/staff              | 200
			""")
	void 역할별_권한(String role, String method, String path, int expected) throws Exception {
		businessDate("2026-06-12");
		MockHttpSession head = login("HEAD");
		String collectorId = newStaff("COLLECTOR", 2, false);
		long customerId = customer(head, "권한");
		long account = openLoan(head, customerId, "P1", 10_000_000, "19.9", 12, 0, 10, "2026-01-10",
				staffId(collectorId));
		MockHttpSession session = role.equals("COLLECTOR") ? loginAs(collectorId) : login(role);
		String url = path.replace("{id}", String.valueOf(account)).replace("{cid}", String.valueOf(customerId));
		Object body = switch (path) {
			case "/api/payments" -> Map.of("accountId", account, "bankDate", "2026-06-10", "amount", 925_866,
					"bankTxId", tx());
			case "/api/loans/{id}/rate-changes" -> Map.of("rate", "17.9", "validFrom", "2026-06-25", "reason", "인하");
			case "/api/customers/{cid}/reveal" -> Map.of("reason", "본인 확인");
			default -> Map.of();
		};
		int status = method.equals("GET") ? getJson(session, url).andReturn().getResponse().getStatus()
				: postJson(session, url, body).andReturn().getResponse().getStatus();
		assertThat(status).isEqualTo(expected);
	}

	@Test
	void 채권담당은_담당_건만_채권팀장은_팀_전체를_본다() throws Exception {
		businessDate("2026-06-12");
		MockHttpSession head = login("HEAD");
		String mine = newStaff("COLLECTOR", 2, false);
		String other = newStaff("COLLECTOR", 3, false);
		long myLoan = openLoan(head, customer(head, "내 건"), "P1", 10_000_000, "19.9", 12, 0, 10, "2026-01-10",
				staffId(mine));
		long otherLoan = openLoan(head, customer(head, "남의 건"), "P1", 10_000_000, "19.9", 12, 0, 10,
				"2026-01-10", staffId(other));
		MockHttpSession me = loginAs(mine);
		assertThat(getJson(me, "/api/loans/" + myLoan).andReturn().getResponse().getStatus()).isEqualTo(200);
		assertThat(getJson(me, "/api/loans/" + otherLoan).andReturn().getResponse().getStatus()).isEqualTo(403);

		MockHttpSession team2Lead = login("COLLECTION_LEAD", 2);
		assertThat(getJson(team2Lead, "/api/loans/" + myLoan).andReturn().getResponse().getStatus()).isEqualTo(200);
		assertThat(getJson(team2Lead, "/api/loans/" + otherLoan).andReturn().getResponse().getStatus())
				.isEqualTo(403);
	}

	@Test
	void 식별번호는_암호문과_블라인드_인덱스로만_저장되고_원문_조회는_기록된다() throws Exception {
		MockHttpSession head = login("HEAD");
		long id = customer(head, "암호화");
		var row = jdbc.sql("select resident_no_enc, resident_no_bidx from customer where id = :id").param("id", id)
				.query((rs, n) -> new String[] { rs.getString(1), rs.getString(2) }).single();
		assertThat(row[0]).startsWith("v1:").doesNotContain("800101");
		assertThat(row[1]).hasSize(64);
		assertThat(ok(getJson(head, "/api/customers/" + id)).get("residentNoMasked").asString())
				.endsWith("******");

		ok(postJson(head, "/api/customers/" + id + "/reveal", Map.of("reason", "본인 확인")));
		long logs = jdbc.sql("select count(*) from access_log where action = 'CUSTOMER_ID_REVEAL' and target_id = :t")
				.param("t", String.valueOf(id)).query(Long.class).single();
		assertThat(logs).isEqualTo(1);
	}
}
