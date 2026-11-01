package dev.arche;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arche.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

/** 신용정보 보고 일 배치 (R61): 파일 생성 → 전송 → 결과 반영, 오류 레코드 재보고, 같은 기준일 재생성 금지 */
class CreditReportIT extends IntegrationTest {

	@Test
	void 보고_파일을_만들고_정상_결과만_반영해_오류는_다음_날_다시_보낸다() throws Exception {
		businessDate("2027-03-10");
		MockHttpSession head = login("HEAD");
		MockHttpSession admin = login("SYSTEM_ADMIN");
		long loan = openLoan(head, customer(head, "보고대상"), "P1", 5_000_000, "19.9", 12, 0, 10, "2027-03-02", null);

		assertThat(postJson(head, "/api/credit-reports/run", Map.of("baseDate", "2027-03-09")).andReturn()
				.getResponse().getStatus()).isEqualTo(403);
		JsonNode file = ok(postJson(admin, "/api/credit-reports/run", Map.of("baseDate", "2027-03-09")));
		assertThat(file.get("status").asString()).isEqualTo("RESULT_RECEIVED");
		assertThat(file.get("fileName").asString()).isEqualTo("CRPT_20270309_ARCHE001.dat");
		int count = file.get("recordCount").asInt();
		assertThat(count).isPositive();
		assertThat(file.get("byteLength").asInt()).isEqualTo((count + 2) * 122);

		JsonNode detail = ok(getJson(admin, "/api/credit-reports/" + file.get("id").asLong()));
		JsonNode mine = null;
		int errors = 0;
		for (JsonNode r : detail.get("records")) {
			if (r.get("accountId").asLong() == loan) {
				mine = r;
			}
			if (!"0000".equals(r.get("resultCode").asString())) {
				errors++;
			}
		}
		assertThat(mine).isNotNull();
		assertThat(mine.get("recordType").asString()).isEqualTo("OPEN");
		assertThat(detail.get("file").get("errorCount").asInt()).isEqualTo(errors);

		// 같은 기준일은 다시 만들지 않는다
		assertThat(postJson(admin, "/api/credit-reports/run", Map.of("baseDate", "2027-03-09")).andReturn()
				.getResponse().getStatus()).isEqualTo(409);

		// 다음 날: 정상 반영된 개설은 다시 보내지 않고, 오류였던 레코드만 다시 나간다
		JsonNode next = ok(postJson(admin, "/api/credit-reports/run", Map.of("baseDate", "2027-03-10")));
		// (다른 테스트의 계약이 그사이 연체 90일째가 될 수 있어 "이상"으로 본다)
		assertThat(next.get("recordCount").asInt()).isGreaterThanOrEqualTo(errors);
		boolean mineAgain = false;
		for (JsonNode r : ok(getJson(admin, "/api/credit-reports/" + next.get("id").asLong())).get("records")) {
			mineAgain |= r.get("accountId").asLong() == loan;
		}
		assertThat(mineAgain).isEqualTo(!"0000".equals(mine.get("resultCode").asString()));

		byte[] raw = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.get("/api/credit-reports/" + file.get("id").asLong() + "/file").session(admin)).andReturn()
				.getResponse().getContentAsByteArray();
		assertThat(raw).hasSize(file.get("byteLength").asInt());
		assertThat((char) raw[0]).isEqualTo('H');
	}
}
