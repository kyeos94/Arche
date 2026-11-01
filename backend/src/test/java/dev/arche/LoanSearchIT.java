package dev.arche;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arche.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

/** 계약 목록 상세 검색·정렬·페이징, 문자 일괄 발송 (ADR 0011) */
class LoanSearchIT extends IntegrationTest {

	@Test
	void 담당자_금액_범위_성명으로_찾고_서버에서_정렬하고_페이지를_나눈다() throws Exception {
		businessDate("2026-07-01");
		MockHttpSession head = login("HEAD");
		long collector = staffId(newStaff("COLLECTOR", 2, false));
		String unique = "검색" + System.nanoTime() % 100000;
		long small = openLoan(head, customer(head, unique + "가"), "P1", 2_000_000, "19.9", 12, 0, 10, "2026-07-01",
				collector);
		long middle = openLoan(head, customer(head, unique + "나"), "P1", 5_000_000, "19.9", 12, 0, 10, "2026-07-01",
				collector);
		long large = openLoan(head, customer(head, unique + "다"), "P2", 9_000_000, "19.0", 6, 0, 10, "2026-07-01",
				collector);

		String mine = "/api/loans?assignedStaffId=" + collector;
		assertThat(ids(ok(getJson(head, mine + "&sort=principalBalance&dir=asc")))).containsExactly(small, middle, large);
		assertThat(ids(ok(getJson(head, mine + "&sort=principalBalance&dir=desc")))).containsExactly(large, middle, small);
		JsonNode first = ok(getJson(head, mine + "&sort=principalBalance&dir=asc&size=20&page=0"));
		assertThat(first.get("totalElements").asLong()).isEqualTo(3);
		assertThat(first.get("size").asInt()).isEqualTo(20);
		assertThat(first.get("content").get(0).get("assignedStaffName").asString()).isEqualTo("테스트 COLLECTOR");

		assertThat(ids(ok(getJson(head, mine + "&principalMin=3000000&principalMax=9000000&sort=principalBalance"))))
				.containsExactly(middle, large);
		assertThat(ids(ok(getJson(head, mine + "&productCode=P2")))).containsExactly(large);
		assertThat(ids(ok(getJson(head, "/api/loans?keywordType=NAME&keyword=" + unique + "&sort=accountNo&dir=asc"))))
				.containsExactly(small, middle, large);
		assertThat(ids(ok(getJson(head, "/api/loans?keywordType=NAME&keyword=없는이름" + unique)))).isEmpty();
		// 허용하지 않는 정렬 키는 기본 순서(최신순)로
		assertThat(ids(ok(getJson(head, mine + "&sort=customer_id;drop&dir=asc")))).containsExactly(large,
				middle, small);
		// 예전 검색창(q)도 계속 동작
		String accountNo = ok(getJson(head, mine + "&sort=principalBalance&dir=asc")).get("content").get(0)
				.get("accountNo").asString();
		assertThat(ids(ok(getJson(head, "/api/loans?q=" + accountNo)))).containsExactly(small);
	}

	@Test
	void 문자_일괄_발송은_계약마다_규칙을_적용하고_범위_밖_계약은_건너뛴다() throws Exception {
		businessDate("2026-07-01");
		MockHttpSession head = login("HEAD");
		String leadId = newStaff("COLLECTION_LEAD", 2, false);
		MockHttpSession lead = loginAs(leadId);
		long mine1 = staffId(newStaff("COLLECTOR", 2, false));
		long other = staffId(newStaff("COLLECTOR", 3, false));
		long a = openLoan(head, customer(head, "일괄문자가"), "P1", 2_000_000, "19.9", 12, 0, 10, "2026-07-01", mine1);
		long b = openLoan(head, customer(head, "일괄문자나"), "P1", 2_000_000, "19.9", 12, 0, 10, "2026-07-01", mine1);
		long outside = openLoan(head, customer(head, "일괄문자다"), "P1", 2_000_000, "19.9", 12, 0, 10, "2026-07-01",
				other);

		JsonNode r = ok(postJson(lead, "/api/dispatches/bulk-sms",
				Map.of("accountIds", List.of(a, b, outside), "body", "납입일 안내드립니다")));
		assertThat(r.get("sent").asInt() + r.get("scheduled").asInt()).isEqualTo(2);
		assertThat(r.get("skipped").asInt()).isEqualTo(1);
		JsonNode skipped = r.get("items").get(2);
		assertThat(skipped.get("accountId").asLong()).isEqualTo(outside);
		assertThat(skipped.get("result").asString()).isEqualTo("SKIPPED");

		// 계약 상세의 발송 이력에도 남는다
		assertThat(ok(getJson(lead, "/api/loans/" + a + "/dispatches")).size()).isEqualTo(1);
		// 출납은 추심 연락 권한이 없다
		assertThat(postJson(login("TELLER"), "/api/dispatches/bulk-sms", Map.of("accountIds", List.of(a), "body", "x"))
				.andReturn().getResponse().getStatus()).isEqualTo(403);
	}

	private static List<Long> ids(JsonNode page) {
		List<Long> ids = new ArrayList<>();
		page.get("content").forEach(n -> ids.add(n.get("id").asLong()));
		return ids;
	}
}
