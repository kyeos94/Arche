package dev.arche;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.arche.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

/**
 * 접수 채널: 중개업자 API·중개수수료(R56), 대출 비교 플랫폼 3단계(R57), 본인인증 랜딩(R58).
 */
class OriginationChannelIT extends IntegrationTest {

	@Value("${arche.integration.platform.mock.api-key}")
	String platformKey;

	private static long seq = System.nanoTime() % 10_000;

	static String residentNo(int lastDigit) {
		return "870707-2" + String.format("%05d", (++seq) % 100_000) + lastDigit;
	}

	MockHttpServletResponse external(String url, String header, String key, Object body) throws Exception {
		var req = post(url).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
		if (header != null) {
			req = req.header(header, key);
		}
		return mvc.perform(req).andReturn().getResponse();
	}

	JsonNode okBody(MockHttpServletResponse r) throws Exception {
		assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
		return json.readTree(r.getContentAsString());
	}

	JsonNode channel(MockHttpSession s, long appId) throws Exception {
		return ok(getJson(s, "/api/applications/" + appId + "/channel"));
	}

	long appId(String applicationNo) {
		return jdbc.sql("select id from loan_application where application_no = :n").param("n", applicationNo)
				.query(Long.class).single();
	}

	void verifyRequiredDocuments(MockHttpSession s, long appId) throws Exception {
		for (JsonNode d : ok(getJson(s, "/api/applications/" + appId + "/documents"))) {
			if (!d.get("required").asBoolean()) {
				continue;
			}
			String doc = "/api/applications/" + appId + "/documents/" + d.get("id").asLong();
			if (d.get("status").asString().equals("REQUESTED")) {
				ok(postJson(s, doc + "/paper", Map.of("note", "원본 대조")));
			}
			ok(postJson(s, doc + "/verify", Map.of()));
		}
	}

	// ------------------------------------------------------------------ 중개업자 (R56)

	@Test
	void 중개업자_API_접수부터_수수료_정산과_조기_완제_환수까지() throws Exception {
		businessDate("2026-11-02");
		newStaff("UNDERWRITER", 9L, false);
		MockHttpSession lead = login("UNDERWRITING_LEAD");
		MockHttpSession head = login("HEAD");
		MockHttpSession uw = login("UNDERWRITER");
		assertThat(postJson(uw, "/api/brokers", Map.of("name", "x", "registrationNo", "x", "rate", "1.00"))
				.andReturn().getResponse().getStatus()).as("심사역은 중개업자를 등록할 수 없다").isEqualTo(403);
		JsonNode issued = ok(postJson(lead, "/api/brokers",
				Map.of("name", "가상중개", "registrationNo", "IT-" + System.nanoTime(), "rate", "3.00")));
		String key = issued.get("apiKey").asString();
		long brokerId = issued.get("broker").get("id").asLong();
		assertThat(postJson(lead, "/api/brokers", Map.of("name", "y", "registrationNo", "y2", "rate", "4.50"))
				.andReturn().getResponse().getStatus()).as("수수료율 상한 4%").isEqualTo(422);

		Map<String, Object> body = new HashMap<>();
		body.put("brokerRef", "BR-" + System.nanoTime());
		body.put("name", "중개고객");
		body.put("residentNo", residentNo(2));
		body.put("birthDate", "1987-07-07");
		body.put("phone", "010-2222-3333");
		body.put("productCode", "P5");
		body.put("requestedAmount", 5_000_000);
		body.put("termMonths", 12);
		body.put("graceMonths", 0);
		body.put("dueDay", 25);
		body.put("creditInquiryConsent", true);
		String url = "/api/integration/broker/applications";
		assertThat(external(url, "X-Broker-Key", "brk_wrong", body).getStatus()).isEqualTo(401);
		JsonNode intake = okBody(external(url, "X-Broker-Key", key, body));
		assertThat(intake.get("status").asString()).as("비대면 채널은 사전심사·배정까지 자동").isEqualTo("ASSIGNED");
		String applicationNo = intake.get("applicationNo").asString();
		assertThat(okBody(external(url, "X-Broker-Key", key, body)).get("applicationNo").asString())
				.as("같은 중개 신청 번호는 다시 만들지 않는다").isEqualTo(applicationNo);

		var upload = mvc.perform(multipart(url + "/" + applicationNo + "/documents/ID_CARD")
				.file(new MockMultipartFile("file", "id.pdf", "application/pdf",
						"%PDF-1.7 id".getBytes(StandardCharsets.UTF_8)))
				.header("X-Broker-Key", key)).andReturn().getResponse();
		assertThat(upload.getStatus()).as(upload.getContentAsString()).isEqualTo(200);
		String otherKey = ok(postJson(lead, "/api/brokers",
				Map.of("name", "다른중개", "registrationNo", "IT2-" + System.nanoTime(), "rate", "1.00")))
				.get("apiKey").asString();
		assertThat(mvc.perform(get(url + "/" + applicationNo).header("X-Broker-Key", otherKey)).andReturn()
				.getResponse().getStatus()).as("다른 중개업자의 신청은 볼 수 없다").isEqualTo(404);

		long app = appId(applicationNo);
		ok(postJson(lead, "/api/applications/" + app + "/transitions", Map.of("to", "UNDERWRITING")));
		verifyRequiredDocuments(lead, app);
		ok(postJson(lead, "/api/applications/" + app + "/approval", Map.of("amount", 5_000_000, "rate", "19.5")));
		ok(postJson(lead, "/api/applications/" + app + "/transitions", Map.of("to", "CONTRACTED")));
		ok(postJson(lead, "/api/applications/" + app + "/disbursement", Map.of()));

		JsonNode ch = channel(lead, app);
		assertThat(ch.get("broker").get("id").asLong()).isEqualTo(brokerId);
		assertThat(ch.get("commissions")).hasSize(1);
		assertThat(ch.get("commissions").get(0).get("amount").asLong()).as("500만원 × 3%").isEqualTo(150_000);

		Long accountId = null;
		for (int i = 0; i < 100 && accountId == null; i++) {
			accountId = jdbc.sql("select id from loan_account where application_id = :a").param("a", app)
					.query(Long.class).optional().orElse(null);
			if (accountId == null) {
				Thread.sleep(100);
			}
		}
		assertThat(accountId).isNotNull();
		long payoff = detail(head, accountId).get("payoffAmount").asLong();
		pay(head, accountId, "2026-11-02", payoff, tx());
		assertThat(detail(head, accountId).get("status").asString()).isEqualTo("PAID_OFF");

		ok(postJson(lead, "/api/brokers/commissions/clawback-scan", Map.of()));
		JsonNode after = channel(lead, app).get("commissions");
		assertThat(after).hasSize(2);
		assertThat(after.get(1).get("entryType").asString()).isEqualTo("CLAWBACK");
		assertThat(after.get(1).get("amount").asLong()).isEqualTo(-150_000);
		assertThat(after.get(1).get("reasonCode").asString()).isEqualTo("EARLY_PAYOFF");
		assertThat(postJson(lead, "/api/brokers/commissions/applications/" + app + "/mis-selling",
				Map.of("reason", "설명 누락")).andReturn().getResponse().getStatus()).as("환수는 1회").isEqualTo(409);

		JsonNode detail = ok(getJson(lead, "/api/brokers/" + brokerId));
		assertThat(detail.get("settledTotal").asLong() + detail.get("clawbackTotal").asLong()).isZero();
		var rate = postJson(lead, "/api/brokers/" + brokerId + "/rates",
				Map.of("rate", "2.00", "validFrom", "2026-11-01", "reason", "소급")).andReturn().getResponse();
		assertThat(rate.getStatus()).as("수수료율 소급 변경 금지").isEqualTo(422);
		ok(postJson(lead, "/api/brokers/" + brokerId + "/rates",
				Map.of("rate", "2.00", "validFrom", "2026-12-01", "reason", "재계약")));
		assertThat(ok(getJson(lead, "/api/brokers/" + brokerId)).get("rates")).hasSize(2);
	}

	// ------------------------------------------------------------------ 플랫폼 (R57)

	Map<String, Object> limitBody(String ref, String residentNo, boolean consent) {
		Map<String, Object> b = new HashMap<>();
		b.put("platformRef", ref);
		b.put("name", "플랫폼고객");
		b.put("residentNo", residentNo);
		b.put("birthDate", "1987-07-07");
		b.put("productCode", "P1");
		b.put("requestedAmount", 20_000_000);
		b.put("termMonths", 24);
		b.put("creditInquiryConsent", consent);
		b.put("consentRef", consent ? "PF-CONSENT-1" : null);
		return b;
	}

	Map<String, Object> applyBody(String ref, String residentNo, long amount) {
		Map<String, Object> b = new HashMap<>();
		b.put("platformRef", ref);
		b.put("name", "플랫폼고객");
		b.put("residentNo", residentNo);
		b.put("birthDate", "1987-07-07");
		b.put("phone", "010-4444-5555");
		b.put("requestedAmount", amount);
		b.put("termMonths", 24);
		b.put("graceMonths", 0);
		b.put("dueDay", 15);
		return b;
	}

	@Test
	void 플랫폼_한도조회_신청_결과_통보_3단계() throws Exception {
		businessDate("2026-11-02");
		newStaff("UNDERWRITER", 9L, false);
		MockHttpSession lead = login("UNDERWRITING_LEAD");
		String base = "/api/integration/platform/MOCKPLAT";
		String ref = "PF-" + System.nanoTime();
		String rn = residentNo(5);

		assertThat(external(base + "/limit-inquiries", "X-Platform-Key", "wrong", limitBody(ref, rn, true))
				.getStatus()).isEqualTo(401);
		assertThat(external(base + "/limit-inquiries", "X-Platform-Key", platformKey, limitBody(ref, rn, false))
				.getStatus()).as("동의 없이는 한도조회 불가").isEqualTo(422);
		JsonNode limit = okBody(external(base + "/limit-inquiries", "X-Platform-Key", platformKey,
				limitBody(ref, rn, true)));
		assertThat(limit.get("offered").asBoolean()).isTrue();
		assertThat(limit.get("offeredLimit").asLong()).as("5등급 = 최대 3,000만원 × 60%").isEqualTo(18_000_000);
		assertThat(limit.get("validUntil").asString()).isEqualTo("2026-11-08");
		assertThat(okBody(external(base + "/limit-inquiries", "X-Platform-Key", platformKey,
				limitBody(ref, rn, true))).get("inquiryNo").asString()).isEqualTo(limit.get("inquiryNo").asString());

		var mismatch = external(base + "/applications", "X-Platform-Key", platformKey,
				applyBody(ref, residentNo(5), 10_000_000));
		assertThat(mismatch.getContentAsString()).contains("PLATFORM_IDENTITY_MISMATCH");
		var over = external(base + "/applications", "X-Platform-Key", platformKey, applyBody(ref, rn, 18_000_001));
		assertThat(over.getContentAsString()).contains("PLATFORM_OVER_LIMIT");
		JsonNode applied = okBody(external(base + "/applications", "X-Platform-Key", platformKey,
				applyBody(ref, rn, 10_000_000)));
		assertThat(applied.get("status").asString()).isEqualTo("ASSIGNED");
		long app = appId(applied.get("applicationNo").asString());

		ok(postJson(lead, "/api/applications/" + app + "/transitions", Map.of("to", "UNDERWRITING")));
		ok(postJson(lead, "/api/applications/" + app + "/transitions",
				Map.of("to", "REJECTED", "reason", "소득 확인 불가", "reasonCode", "INCOME_UNVERIFIED")));
		JsonNode notices = awaitNotices(lead, app);
		assertThat(notices.get(0).get("result").asString()).isEqualTo("REJECTED");
		assertThat(notices.get(0).get("delivered").asBoolean()).isTrue();
		var received = mvc.perform(get("/api/integration/mock-platform/notices").header("X-Platform-Key",
				platformKey)).andReturn().getResponse();
		assertThat(received.getContentAsString()).contains(applied.get("applicationNo").asString());

		// 전달 실패를 흉내 내는 참조 → 재통보 때 시도 횟수 증가
		String failRef = "FAIL-" + System.nanoTime();
		String rn2 = residentNo(1);
		okBody(external(base + "/limit-inquiries", "X-Platform-Key", platformKey, limitBody(failRef, rn2, true)));
		long failApp = appId(okBody(external(base + "/applications", "X-Platform-Key", platformKey,
				applyBody(failRef, rn2, 5_000_000))).get("applicationNo").asString());
		ok(postJson(lead, "/api/applications/" + failApp + "/transitions", Map.of("to", "WITHDRAWN")));
		JsonNode failed = awaitNotices(lead, failApp);
		assertThat(failed.get(0).get("delivered").asBoolean()).isFalse();
		JsonNode resent = ok(postJson(lead, "/api/applications/" + failApp + "/platform-notices/resend", Map.of()));
		assertThat(resent.get(0).get("attempts").asInt()).isEqualTo(2);

		String badRef = "PF-BAD-" + System.nanoTime();
		String rn3 = residentNo(9);
		JsonNode declined = okBody(external(base + "/limit-inquiries", "X-Platform-Key", platformKey,
				limitBody(badRef, rn3, true)));
		assertThat(declined.get("offered").asBoolean()).isFalse();
		assertThat(external(base + "/applications", "X-Platform-Key", platformKey,
				applyBody(badRef, rn3, 5_000_000)).getContentAsString()).contains("PLATFORM_NOT_OFFERED");
	}

	JsonNode awaitNotices(MockHttpSession s, long app) throws Exception {
		for (int i = 0; i < 100; i++) {
			JsonNode n = channel(s, app).get("platformNotices");
			if (!n.isEmpty()) {
				return n;
			}
			Thread.sleep(100);
		}
		throw new AssertionError("결과 통보가 없습니다");
	}

	// ------------------------------------------------------------------ 본인인증 랜딩 (R58)

	@Test
	void 랜딩_링크로_본인인증한_뒤에만_동의와_서류를_제출한다() throws Exception {
		businessDate("2026-11-02");
		MockHttpSession uw = login("UNDERWRITER");
		long customerId = ok(postJson(uw, "/api/customers", Map.of("name", "랜딩고객", "residentNo", residentNo(4),
				"birthDate", "1987-07-07", "phone", "010-7777-8888", "address", "가상시"))).get("id").asLong();
		Map<String, Object> app = new HashMap<>();
		app.put("customerId", customerId);
		app.put("productCode", "P2");
		app.put("requestedAmount", 3_000_000);
		app.put("termMonths", 6);
		app.put("graceMonths", 0);
		app.put("dueDay", 10);
		long appId = ok(postJson(uw, "/api/applications", app)).get("id").asLong();

		String oldPath = ok(postJson(uw, "/api/applications/" + appId + "/landing-links", Map.of())).get("path")
				.asString();
		JsonNode issued = ok(postJson(uw, "/api/applications/" + appId + "/landing-links", Map.of()));
		String path = issued.get("path").asString();
		String token = path.substring("/landing/".length());
		String api = "/api/integration/landing/" + token;
		assertThat(mvc.perform(get("/api/integration/landing/" + oldPath.substring("/landing/".length())))
				.andReturn().getResponse().getStatus()).as("재발급하면 이전 링크는 무효").isEqualTo(422);

		JsonNode view = okBody(mvc.perform(get(api)).andReturn().getResponse());
		assertThat(view.get("customerName").asString()).isEqualTo("랜**객");
		assertThat(view.get("status").asString()).isEqualTo("ISSUED");
		assertThat(view.get("documents")).as("본인인증 전에는 서류 목록을 보여주지 않음").isEmpty();
		assertThat(external(api + "/consents", null, null, Map.of("creditInquiry", true)).getContentAsString())
				.contains("IDENTITY_REQUIRED");

		String tx = okBody(external(api + "/identity", null, null, Map.of())).get("transactionId").asString();
		okBody(external("/api/integration/mock-identity/" + tx, null, null,
				Map.of("success", true, "name", "다른사람", "birthDate", "1987-07-07")));
		var mismatch = external(api + "/identity/result", null, null, Map.of("transactionId", tx));
		assertThat(mismatch.getContentAsString()).contains("IDENTITY_MISMATCH");

		String tx2 = okBody(external(api + "/identity", null, null, Map.of())).get("transactionId").asString();
		JsonNode redirect = okBody(external("/api/integration/mock-identity/" + tx2, null, null,
				Map.of("success", true, "name", "랜딩고객", "birthDate", "1987-07-07")));
		assertThat(redirect.get("redirectUrl").asString()).isEqualTo(path + "?tx=" + tx2);
		JsonNode verified = okBody(external(api + "/identity/result", null, null, Map.of("transactionId", tx2)));
		assertThat(verified.get("status").asString()).isEqualTo("VERIFIED");
		assertThat(verified.get("documents")).hasSize(3);

		assertThat(okBody(external(api + "/consents", null, null, Map.of("creditInquiry", true)))
				.get("consentGiven").asBoolean()).isTrue();
		var upload = mvc.perform(multipart(api + "/documents/ID_CARD").file(new MockMultipartFile("file", "id.png",
				"image/png", new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A }))).andReturn().getResponse();
		assertThat(upload.getStatus()).as(upload.getContentAsString()).isEqualTo(200);
		assertThat(json.readTree(upload.getContentAsString()).get("documents").get(0).get("status").asString())
				.isEqualTo("RECEIVED");
		okBody(external(api + "/complete", null, null, Map.of()));
		assertThat(mvc.perform(get(api)).andReturn().getResponse().getStatus()).as("완료 후 재사용 불가")
				.isEqualTo(422);

		JsonNode screened = ok(postJson(uw, "/api/applications/" + appId + "/transitions",
				Map.of("to", "PRE_SCREENING")));
		assertThat(screened.get("status").asString()).isEqualTo("PRE_SCREENING");
		JsonNode review = ok(getJson(uw, "/api/applications/" + appId + "/review"));
		assertThat(review.get("creditInquiries").get(0).get("grade").asInt()).as("랜딩 동의로 신용조회").isEqualTo(4);
		JsonNode links = channel(uw, appId).get("landingLinks");
		assertThat(links.get(0).get("status").asString()).isEqualTo("COMPLETED");
		assertThat(links.get(1).get("status").asString()).isEqualTo("REVOKED");
		assertThat(mvc.perform(post("/api/applications/" + appId + "/landing-links").session(uw).with(csrf()))
				.andReturn().getResponse().getStatus()).isEqualTo(200);
	}
}
