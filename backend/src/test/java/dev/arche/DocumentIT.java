package dev.arche;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arche.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

/** 양식 인쇄 (R72) */
class DocumentIT extends IntegrationTest {

	@Test
	void 연체_계약은_독촉장을_채워_주고_인쇄하면_문서번호와_이력이_남는다() throws Exception {
		businessDate("2026-06-12");
		MockHttpSession head = login("HEAD");
		long customerId = customer(head, "독촉");
		// 2026-01-10 실행 후 입금 없음 → 02-10 회차부터 연체
		long late = openLoan(head, customerId, "P1", 10_000_000, "19.9", 12, 0, 10, "2026-01-10", null);
		// 업무일자에 막 실행한 계약은 연체가 아님
		long fresh = openLoan(head, customer(head, "정상"), "P1", 5_000_000, "19.9", 12, 0, 10, "2026-06-12", null);
		String accountNo = detail(head, late).get("accountNo").asString();

		JsonNode templates = ok(getJson(head, "/api/documents/templates"));
		assertThat(templates.toString()).contains("DEMAND_LETTER", "ACCELERATION_NOTICE");

		JsonNode preview = ok(postJson(head, "/api/documents/render",
				Map.of("templateCode", "DEMAND_LETTER", "accountIds", List.of(late, fresh), "purpose", "PREVIEW")));
		assertThat(preview.get("batchNo").isNull()).isTrue();
		assertThat(preview.get("documents")).hasSize(1);
		JsonNode doc = preview.get("documents").get(0);
		assertThat(doc.get("documentNo").asString()).isEqualTo("미리보기");
		assertThat(doc.get("html").asString()).contains("변제 독촉장", accountNo, "독촉").doesNotContain("{{");
		assertThat(preview.get("skipped").get(0).get("reason").asString()).contains("연체 중이 아닙니다");
		assertThat(printCount(late)).isZero();

		JsonNode printed = ok(postJson(head, "/api/documents/render",
				Map.of("templateCode", "DEMAND_LETTER", "accountIds", List.of(late), "purpose", "PRINT")));
		String batchNo = printed.get("batchNo").asString();
		assertThat(batchNo).startsWith("P20260612-");
		assertThat(printed.get("documents").get(0).get("documentNo").asString()).isEqualTo(batchNo + "-001");
		assertThat(printCount(late)).isEqualTo(1);
		assertThat(ok(getJson(head, "/api/documents/print-log/" + late)).get(0).get("templateName").asString())
				.isEqualTo("변제 독촉장");

		long addressUse = jdbc.sql("select count(*) from access_log where action = 'CUSTOMER_ADDRESS_USE' and target_id = :c")
				.param("c", String.valueOf(customerId)).query(Long.class).single();
		assertThat(addressUse).isEqualTo(2);
	}

	@Test
	void 권한과_조회_범위() throws Exception {
		businessDate("2026-06-12");
		MockHttpSession head = login("HEAD");
		String mine = newStaff("COLLECTOR", 2, false);
		long loan = openLoan(head, customer(head, "범위"), "P1", 10_000_000, "19.9", 12, 0, 10, "2026-01-10",
				staffId(mine));
		Map<String, Object> body = Map.of("templateCode", "DEMAND_LETTER", "accountIds", List.of(loan));

		assertThat(postJson(login("TELLER"), "/api/documents/render", body).andReturn().getResponse().getStatus())
				.isEqualTo(403);
		assertThat(ok(postJson(loginAs(mine), "/api/documents/render", body)).get("documents")).hasSize(1);
		JsonNode other = ok(postJson(login("COLLECTOR", 3), "/api/documents/render", body));
		assertThat(other.get("documents")).isEmpty();
		assertThat(other.get("skipped").get(0).get("reason").asString()).contains("조회 권한");
		assertThat(postJson(head, "/api/documents/render", Map.of("templateCode", "NONE", "accountIds", List.of(loan)))
				.andReturn().getResponse().getStatus()).isEqualTo(404);
	}

	private long printCount(long accountId) {
		return jdbc.sql("select count(*) from document_print_log where account_id = :a").param("a", accountId)
				.query(Long.class).single();
	}
}
