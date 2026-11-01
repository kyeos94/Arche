package dev.arche;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import dev.arche.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

/**
 * 심사 상세: 자동 거절 규칙(R53), 신용조회 동의(guide 11.2), 서류 징구(R54), 심사자 배정(R55).
 */
class OriginationIT extends IntegrationTest {

	private static long seq = System.nanoTime() % 10_000;

	/** 끝자리를 정한 주민등록번호의 고객 (Mock 신용조회: 끝자리 9·0 = 현재 연체) */
	long customerEnding(MockHttpSession s, String name, int lastDigit, String birthDate) throws Exception {
		String residentNo = "850505-1" + String.format("%05d", (++seq) % 100_000) + lastDigit;
		return ok(postJson(s, "/api/customers", Map.of("name", name, "residentNo", residentNo, "birthDate",
				birthDate, "phone", "010-1111-2222", "address", "가상시"))).get("id").asLong();
	}

	long apply(MockHttpSession s, long customerId, String product, long amount, int term, boolean consent)
			throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("customerId", customerId);
		body.put("productCode", product);
		body.put("requestedAmount", amount);
		body.put("termMonths", term);
		body.put("graceMonths", 0);
		body.put("dueDay", 25);
		body.put("creditInquiryConsent", consent);
		body.put("consentEvidence", consent ? "서면 동의서" : null);
		return ok(postJson(s, "/api/applications", body)).get("id").asLong();
	}

	JsonNode to(MockHttpSession s, long appId, String status) throws Exception {
		return ok(postJson(s, "/api/applications/" + appId + "/transitions", Map.of("to", status)));
	}

	JsonNode review(MockHttpSession s, long appId) throws Exception {
		return ok(getJson(s, "/api/applications/" + appId + "/review"));
	}

	String outcome(JsonNode review, String rule) {
		for (JsonNode r : review.get("screening")) {
			if (r.get("rule").asString().equals(rule)) {
				return r.get("outcome").asString();
			}
		}
		return null;
	}

	@Test
	void 중복_신청과_최근_30일_거절_이력은_사전심사에서_자동_거절() throws Exception {
		businessDate("2026-11-01");
		MockHttpSession uw = login("UNDERWRITER");
		long c = customerEnding(uw, "중복", 3, "1985-05-05");
		long first = apply(uw, c, "P1", 5_000_000, 24, false);
		assertThat(to(uw, first, "PRE_SCREENING").get("status").asString()).isEqualTo("PRE_SCREENING");

		long second = apply(uw, c, "P1", 3_000_000, 12, false);
		JsonNode rejected = to(uw, second, "PRE_SCREENING");
		assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
		assertThat(rejected.get("rejectReasonCode").asString()).isEqualTo("DUPLICATE_APPLICATION");
		assertThat(rejected.get("decidedOn").asString()).isEqualTo("2026-11-01");
		JsonNode r2 = review(uw, second);
		assertThat(outcome(r2, "DUPLICATE_APPLICATION")).isEqualTo("REJECT");
		assertThat(outcome(r2, "CREDIT_BUREAU")).as("앞 규칙에서 거절되면 조회하지 않음").isEqualTo("SKIPPED");

		to(uw, first, "WITHDRAWN");
		long third = apply(uw, c, "P1", 3_000_000, 12, false);
		JsonNode again = to(uw, third, "PRE_SCREENING");
		assertThat(again.get("rejectReasonCode").asString()).isEqualTo("RECENT_REJECTION");

		businessDate("2026-12-01");
		long fourth = apply(uw, c, "P1", 3_000_000, 12, false);
		assertThat(to(uw, fourth, "PRE_SCREENING").get("status").asString()).as("30일이 지나면 다시 심사")
				.isEqualTo("PRE_SCREENING");
	}

	@Test
	void 신용조회는_유효한_동의가_있을_때만_하고_현재_연체면_거절() throws Exception {
		businessDate("2026-11-01");
		MockHttpSession uw = login("UNDERWRITER");

		long bad = apply(uw, customerEnding(uw, "연체", 9, "1985-05-05"), "P2", 2_000_000, 6, true);
		JsonNode rejected = to(uw, bad, "PRE_SCREENING");
		assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
		assertThat(rejected.get("rejectReasonCode").asString()).isEqualTo("CREDIT_BUREAU");
		JsonNode inquiries = review(uw, bad).get("creditInquiries");
		assertThat(inquiries).hasSize(1);
		assertThat(inquiries.get(0).get("grade").asInt()).isEqualTo(9);
		long used = jdbc.sql("select count(*) from access_log where action = 'CREDIT_INQUIRY_ID_USE'")
				.query(Long.class).single();
		assertThat(used).as("식별번호 원문 사용은 접근 기록").isPositive();

		long good = apply(uw, customerEnding(uw, "우량", 3, "1985-05-05"), "P2", 2_000_000, 6, true);
		assertThat(to(uw, good, "PRE_SCREENING").get("status").asString()).isEqualTo("PRE_SCREENING");
		assertThat(outcome(review(uw, good), "CREDIT_BUREAU")).isEqualTo("PASS");

		long noConsent = apply(uw, customerEnding(uw, "미동의", 9, "1985-05-05"), "P2", 2_000_000, 6, false);
		assertThat(to(uw, noConsent, "PRE_SCREENING").get("status").asString())
				.as("동의가 없으면 조회하지 않고 자동 거절하지 않음").isEqualTo("PRE_SCREENING");
		JsonNode r = review(uw, noConsent);
		assertThat(outcome(r, "CREDIT_BUREAU")).isEqualTo("SKIPPED");
		assertThat(r.get("creditInquiries")).isEmpty();
		var denied = postJson(uw, "/api/applications/" + noConsent + "/credit-inquiries", Map.of()).andReturn()
				.getResponse();
		assertThat(denied.getStatus()).isEqualTo(422);
		assertThat(denied.getContentAsString()).contains("CONSENT_REQUIRED");

		JsonNode consent = ok(postJson(uw, "/api/applications/" + noConsent + "/consents",
				Map.of("evidence", "녹취 2026-11-01")));
		JsonNode inquiry = ok(postJson(uw, "/api/applications/" + noConsent + "/credit-inquiries", Map.of()));
		assertThat(inquiry.get("currentlyDelinquent").asBoolean()).isTrue();
		assertThat(review(uw, noConsent).get("application").get("status").asString())
				.as("수동 조회는 자동 거절하지 않는다").isEqualTo("PRE_SCREENING");

		ok(postJson(uw, "/api/applications/" + noConsent + "/consents/" + consent.get("id").asLong()
				+ "/withdrawal", Map.of()));
		assertThat(postJson(uw, "/api/applications/" + noConsent + "/credit-inquiries", Map.of()).andReturn()
				.getResponse().getStatus()).as("철회 후 조회 불가").isEqualTo(422);
	}

	@Test
	void 필수_서류를_모두_확인해야_승인할_수_있다() throws Exception {
		businessDate("2026-11-01");
		MockHttpSession uw = login("UNDERWRITER");
		long app = apply(uw, customerEnding(uw, "서류", 2, "1985-05-05"), "P1", 6_000_000, 24, false);
		to(uw, app, "PRE_SCREENING");
		to(uw, app, "ASSIGNED");
		to(uw, app, "UNDERWRITING");
		JsonNode docs = ok(getJson(uw, "/api/applications/" + app + "/documents"));
		assertThat(docs).extracting(d -> d.get("docType").asString()).containsExactly("ID_CARD", "INCOME_PROOF",
				"EMPLOYMENT_PROOF");

		var blocked = postJson(uw, "/api/applications/" + app + "/approval", Map.of("amount", 6_000_000, "rate",
				"19.9")).andReturn().getResponse();
		assertThat(blocked.getStatus()).isEqualTo(422);
		assertThat(blocked.getContentAsString()).contains("DOCUMENTS_INCOMPLETE");

		long idCard = docs.get(0).get("id").asLong();
		String base = "/api/applications/" + app + "/documents/";
		byte[] pdf = "%PDF-1.7 가상 신분증".getBytes(StandardCharsets.UTF_8);
		JsonNode uploaded = ok(mvc.perform(multipart(base + idCard + "/file")
				.file(new MockMultipartFile("file", "신분증.pdf", "application/pdf", pdf)).session(uw).with(csrf())));
		assertThat(uploaded.get("status").asString()).isEqualTo("RECEIVED");
		assertThat(uploaded.get("sizeBytes").asLong()).isEqualTo(pdf.length);
		var file = getJson(uw, base + idCard + "/file").andReturn().getResponse();
		assertThat(file.getContentAsByteArray()).isEqualTo(pdf);

		var fake = mvc.perform(multipart(base + idCard + "/file")
				.file(new MockMultipartFile("file", "x.pdf", "application/pdf", "MZ-exe".getBytes()))
				.session(uw).with(csrf())).andReturn().getResponse();
		assertThat(fake.getStatus()).as("형식과 내용이 다른 파일").isEqualTo(422);

		ok(postJson(uw, base + idCard + "/reject", Map.of("note", "사진이 흐림")));
		ok(mvc.perform(multipart(base + idCard + "/file")
				.file(new MockMultipartFile("file", "신분증2.pdf", "application/pdf", pdf)).session(uw).with(csrf())));
		for (JsonNode d : docs) {
			if (!d.get("required").asBoolean()) {
				continue;
			}
			long id = d.get("id").asLong();
			if (id != idCard) {
				ok(postJson(uw, base + id + "/paper", Map.of("note", "원본 대조")));
			}
			ok(postJson(uw, base + id + "/verify", Map.of()));
		}
		assertThat(review(uw, app).get("missingRequiredDocuments")).isEmpty();
		JsonNode approved = ok(postJson(uw, "/api/applications/" + app + "/approval",
				Map.of("amount", 6_000_000, "rate", "19.9")));
		assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
		assertThat(postJson(uw, base + idCard + "/reject", Map.of("note", "x")).andReturn().getResponse()
				.getStatus()).as("승인 후에는 서류를 바꿀 수 없다").isEqualTo(422);
	}

	@Test
	void 배정은_처리_건수가_적은_심사역_동률이면_마지막_배정이_오래된_순_재배정은_팀장만() throws Exception {
		businessDate("2026-11-01");
		MockHttpSession lead = login("UNDERWRITING_LEAD");
		String uw1Login = newStaff("UNDERWRITER", 9L, false);
		String uw2Login = newStaff("UNDERWRITER", 9L, false);
		long uw1 = staffId(uw1Login);
		long uw2 = staffId(uw2Login);
		MockHttpSession uw = loginAs(uw1Login);

		// 다른 테스트가 만든 심사역은 잠시 배정 불가로 돌려 후보를 두 명으로 좁힌다
		List<Long> others = new ArrayList<>();
		for (JsonNode u : ok(getJson(lead, "/api/underwriters")).get("underwriters")) {
			long id = u.get("staffId").asLong();
			if (u.get("role").asString().equals("UNDERWRITER") && id != uw1 && id != uw2 && u.get("available")
					.asBoolean()) {
				others.add(id);
				ok(postJson(lead, "/api/underwriters/" + id + "/profile",
						Map.of("productCodes", List.of(), "available", false)));
			}
		}
		try {
			List<Long> assigned = new ArrayList<>();
			for (int i = 0; i < 3; i++) {
				long app = apply(uw, customerEnding(uw, "배정" + i, 1, "1985-05-05"), "P1", 5_000_000, 24, false);
				to(uw, app, "PRE_SCREENING");
				assigned.add(to(uw, app, "ASSIGNED").get("underwriterId").asLong());
				if (i == 2) {
					var forbidden = postJson(uw, "/api/applications/" + app + "/assignment",
							Map.of("underwriterId", uw2, "reason", "휴가")).andReturn().getResponse();
					assertThat(forbidden.getStatus()).isEqualTo(403);
					JsonNode moved = ok(postJson(lead, "/api/applications/" + app + "/assignment",
							Map.of("underwriterId", uw2, "reason", "휴가")));
					assertThat(moved.get("underwriterId").asLong()).isEqualTo(uw2);
					JsonNode history = ok(getJson(lead, "/api/applications/" + app + "/history"));
					assertThat(history.get(history.size() - 1).get("reason").asString()).startsWith("재배정");
				}
			}
			assertThat(assigned).containsExactly(uw1, uw2, uw1);

			ok(postJson(lead, "/api/underwriters/" + uw1 + "/profile",
					Map.of("productCodes", List.of("P3"), "available", true)));
			long p1 = apply(uw, customerEnding(uw, "담당", 1, "1985-05-05"), "P1", 5_000_000, 24, false);
			to(uw, p1, "PRE_SCREENING");
			assertThat(to(uw, p1, "ASSIGNED").get("underwriterId").asLong()).as("P1 담당이 아닌 uw1은 제외")
					.isEqualTo(uw2);
		} finally {
			for (long id : others) {
				ok(postJson(lead, "/api/underwriters/" + id + "/profile",
						Map.of("productCodes", List.of("P1", "P2", "P3", "P4", "P5"), "available", true)));
			}
		}
	}
}
