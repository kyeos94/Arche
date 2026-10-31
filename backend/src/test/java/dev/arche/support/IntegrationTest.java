package dev.arche.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 통합 테스트 기반 (Testcontainers PostgreSQL, CI 전용 — ./gradlew integrationTest).
 * 컨텍스트와 컨테이너는 테스트 클래스 사이에서 공유하므로, 각 테스트는 자기 고객·계약을 새로 만들고 업무일자를 명시적으로 정한다.
 */
@Tag("integration")
@SpringBootTest(properties = { "arche.demo.enabled=false", "arche.scheduling.enabled=false" })
@AutoConfigureMockMvc
@Import(IntegrationTest.Containers.class)
public abstract class IntegrationTest {

	public static final String PASSWORD = "Test-Password-1!";

	@TestConfiguration(proxyBeanMethods = false)
	static class Containers {

		@Bean
		@ServiceConnection
		PostgreSQLContainer postgres() {
			return new PostgreSQLContainer("postgres:18-alpine");
		}
	}

	@Autowired
	protected MockMvc mvc;

	@Autowired
	protected JdbcClient jdbc;

	@Autowired
	protected JsonMapper json;

	@Autowired
	private PasswordEncoder encoder;

	private static int staffSeq;

	protected void businessDate(String date) {
		jdbc.sql("update business_date set current_date_ = :d where id = 1").param("d", LocalDate.parse(date))
				.update();
	}

	/** 역할별 직원을 새로 만들고 로그인한 세션을 돌려준다 */
	protected MockHttpSession login(String role) throws Exception {
		return login(role, role.equals("COLLECTION_LEAD") || role.equals("COLLECTOR") ? 2L : 9L);
	}

	protected MockHttpSession login(String role, long teamId) throws Exception {
		String loginId = newStaff(role, teamId, false);
		return loginAs(loginId);
	}

	protected String newStaff(String role, long teamId, boolean mustChange) {
		String loginId = "t" + role.toLowerCase().replace("_", "") + (++staffSeq) + System.nanoTime() % 100000;
		jdbc.sql("""
				insert into staff (login_id, name, role, team_id, password_hash, password_changed_at,
				  must_change_password, created_at, created_by, updated_at)
				values (:login, :name, :role, :team, :hash, current_date, :must, now(), 'test', now())
				""").param("login", loginId).param("name", "테스트 " + role).param("role", role)
				.param("team", teamId).param("hash", encoder.encode(PASSWORD)).param("must", mustChange).update();
		return loginId;
	}

	protected long staffId(String loginId) {
		return jdbc.sql("select id from staff where login_id = :l").param("l", loginId).query(Long.class).single();
	}

	protected MockHttpSession loginAs(String loginId) throws Exception {
		MvcResult r = mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("loginId", loginId, "password", PASSWORD)))).andReturn();
		if (r.getResponse().getStatus() != 200) {
			throw new IllegalStateException("로그인 실패 " + loginId + ": " + r.getResponse().getContentAsString());
		}
		return (MockHttpSession) r.getRequest().getSession(false);
	}

	protected ResultActions postJson(MockHttpSession session, String url, Object body) throws Exception {
		return mvc.perform(post(url).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(body)));
	}

	protected ResultActions getJson(MockHttpSession session, String url) throws Exception {
		return mvc.perform(get(url).session(session));
	}

	protected JsonNode body(ResultActions actions) throws Exception {
		return json.readTree(actions.andReturn().getResponse().getContentAsString());
	}

	/** 200이 아니면 응답 본문을 담아 실패 */
	protected JsonNode ok(ResultActions actions) throws Exception {
		var response = actions.andReturn().getResponse();
		if (response.getStatus() != 200) {
			throw new AssertionError("HTTP " + response.getStatus() + ": " + response.getContentAsString());
		}
		return json.readTree(response.getContentAsString());
	}

	private static long residentSeq = System.nanoTime() % 1_000_000;

	/** 새 고객 (성인) */
	protected long customer(MockHttpSession session, String name) throws Exception {
		return customer(session, name, "1980-01-01");
	}

	protected long customer(MockHttpSession session, String name, String birthDate) throws Exception {
		String residentNo = "800101-1" + String.format("%06d", (++residentSeq) % 1_000_000);
		return ok(postJson(session, "/api/customers", Map.of("name", name, "residentNo", residentNo, "birthDate",
				birthDate, "phone", "010-0000-0000", "address", "가상시"))).get("id").asLong();
	}

	protected long openLoan(MockHttpSession head, long customerId, String product, long principal, String rate,
			int term, int grace, int dueDay, String disbursedOn, Long staffId) throws Exception {
		var body = new java.util.HashMap<String, Object>();
		body.put("customerId", customerId);
		body.put("productCode", product);
		body.put("principal", principal);
		body.put("rate", rate);
		body.put("termMonths", term);
		body.put("graceMonths", grace);
		body.put("dueDay", dueDay);
		body.put("disbursedOn", disbursedOn);
		body.put("assignedStaffId", staffId);
		return ok(postJson(head, "/api/loans", body)).get("id").asLong();
	}

	protected JsonNode pay(MockHttpSession session, Long accountId, String bankDate, long amount, String txId)
			throws Exception {
		var body = new java.util.HashMap<String, Object>();
		body.put("accountId", accountId);
		body.put("bankDate", bankDate);
		body.put("amount", amount);
		body.put("bankTxId", txId);
		body.put("depositorName", "테스트");
		return ok(postJson(session, "/api/payments", body));
	}

	protected JsonNode detail(MockHttpSession session, long accountId) throws Exception {
		return ok(getJson(session, "/api/loans/" + accountId));
	}

	protected static String tx() {
		return "IT-" + System.nanoTime();
	}
}
