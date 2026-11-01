package dev.arche;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arche.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

/** 상단 통합 검색: 계약번호·이름·주민등록번호·휴대폰번호, 조회 범위 적용, 접근 기록 */
class SearchIT extends IntegrationTest {

	@Test
	void 계약번호_이름_주민등록번호_휴대폰번호로_찾는다() throws Exception {
		businessDate("2026-06-12");
		MockHttpSession head = login("HEAD");
		String unique = String.format("%06d", System.nanoTime() % 1_000_000);
		String residentNo = "770707-1" + unique;
		String phone = "010-7" + unique.substring(0, 3) + "-" + unique.substring(2, 6);
		long customerId = ok(postJson(head, "/api/customers", Map.of("name", "검색대상" + unique, "residentNo",
				residentNo, "birthDate", "1977-07-07", "phone", phone, "address", "가상시"))).get("id").asLong();
		String collectorId = newStaff("COLLECTOR", 2, false);
		long account = openLoan(head, customerId, "P1", 10_000_000, "19.9", 12, 0, 10, "2026-01-10",
				staffId(collectorId));
		String accountNo = detail(head, account).get("accountNo").asString();

		assertThat(search(head, accountNo)).anySatisfy(h -> assertThat(h.get("id").asLong()).isEqualTo(account));
		assertThat(search(head, "검색대상" + unique)).singleElement()
				.satisfies(h -> assertThat(h.get("matchedBy").asString()).isEqualTo("이름"));
		JsonNode byResident = search(head, residentNo.replace("-", ""));
		assertThat(byResident).singleElement().satisfies(h -> {
			assertThat(h.get("matchedBy").asString()).isEqualTo("주민등록번호");
			assertThat(h.get("residentNoMasked").asString()).endsWith("******");
		});
		assertThat(search(head, phone)).anySatisfy(h -> assertThat(h.get("id").asLong()).isEqualTo(account));

		long logs = jdbc.sql("select count(*) from access_log where action = 'CUSTOMER_SEARCH'").query(Long.class)
				.single();
		assertThat(logs).isGreaterThanOrEqualTo(2);

		// 다른 팀 채권담당에게는 보이지 않는다
		MockHttpSession other = login("COLLECTOR", 3);
		assertThat(search(other, accountNo)).isEmpty();
		assertThat(search(loginAs(collectorId), accountNo)).hasSize(1);
	}

	private JsonNode search(MockHttpSession session, String q) throws Exception {
		// MockMvc는 URL 템플릿을 다시 인코딩하므로 미리 인코딩하지 않고 파라미터로 넘긴다
		return ok(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/loans/search")
				.param("q", q).session(session)));
	}
}
