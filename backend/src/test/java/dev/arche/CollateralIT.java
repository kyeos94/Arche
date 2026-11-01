package dev.arche;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arche.support.IntegrationTest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

/** 담보·시세·LTV와 보증인 (정책서 1장, R60) */
class CollateralIT extends IntegrationTest {

	@Test
	void 차량_담보_시세_조회로_인정가액과_LTV를_보여주고_해지는_완제_후에만() throws Exception {
		businessDate("2026-06-12");
		MockHttpSession head = login("HEAD");
		MockHttpSession lead = login("COLLECTION_LEAD");
		String collectorId = newStaff("COLLECTOR", 2, false);
		MockHttpSession collector = loginAs(collectorId);
		long loan = openLoan(head, customer(head, "차담보"), "P3", 10_000_000, "15.0", 12, 0, 10, "2026-06-01",
				staffId(collectorId));
		String base = "/api/loans/" + loan + "/collaterals";

		Map<String, Object> vehicle = new HashMap<>(Map.of("type", "VEHICLE", "description", "중형 세단 (가상)",
				"identifier", "12가3456", "vehicleClass", "중형", "modelYear", 2023, "seniorClaim", 0, "lienAmount",
				12_000_000));
		assertThat(postJson(collector, base, vehicle).andReturn().getResponse().getStatus()).isEqualTo(403);
		long id = ok(postJson(lead, base, vehicle)).get("id").asLong();

		JsonNode valuation = ok(postJson(lead, base + "/" + id + "/valuations", Map.of()));
		// 32,000,000 × (1 − 3년 × 10%) = 22,400,000
		assertThat(valuation.get("price").asLong()).isEqualTo(22_400_000);

		JsonNode overview = ok(getJson(collector, base));
		JsonNode c = overview.get("collaterals").get(0);
		assertThat(c.get("recognizedValue").asLong()).isEqualTo(15_680_000); // 70%
		assertThat(c.get("stale").asBoolean()).isFalse();
		JsonNode summary = overview.get("summary");
		assertThat(summary.get("totalPrice").asLong()).isEqualTo(22_400_000);
		// 10,000,000 / 22,400,000 = 44.64…% → 44.6
		assertThat(summary.get("ltv").decimalValue()).isEqualByComparingTo("44.6");
		assertThat(summary.get("margin").asLong()).isEqualTo(5_680_000);

		assertThat(postJson(lead, base + "/" + id + "/release", Map.of()).andReturn().getResponse().getStatus())
				.isEqualTo(422);
		Map<String, Object> unknown = new HashMap<>(vehicle);
		unknown.put("vehicleClass", "우주선");
		long other = ok(postJson(lead, base, unknown)).get("id").asLong();
		assertThat(postJson(lead, base + "/" + other + "/valuations", Map.of()).andReturn().getResponse().getStatus())
				.isEqualTo(404);
	}

	@Test
	void 보증인은_식별번호를_암호화해_마스킹으로만_보이고_본인과_중복은_막는다() throws Exception {
		businessDate("2026-06-12");
		MockHttpSession head = login("HEAD");
		String borrowerNo = "750101-1" + String.format("%06d", System.nanoTime() % 1_000_000);
		long customer = ok(postJson(head, "/api/customers", Map.of("name", "주채무자", "residentNo", borrowerNo,
				"birthDate", "1975-01-01", "phone", "010-1111-2222", "address", "가상시"))).get("id").asLong();
		long loan = openLoan(head, customer, "P1", 5_000_000, "19.9", 12, 0, 10, "2026-06-01", null);
		String base = "/api/loans/" + loan + "/guarantors";

		String guarantorNo = "800202-2" + String.format("%06d", System.nanoTime() % 1_000_000);
		Map<String, Object> g = new HashMap<>(Map.of("name", "보증인", "residentNo", guarantorNo, "phone",
				"010-3333-4444", "relation", "배우자", "guaranteeLimit", 6_000_000, "startDate", "2026-06-01"));
		JsonNode saved = ok(postJson(head, base, g));
		assertThat(saved.get("residentNoMasked").asString()).isEqualTo(guarantorNo.substring(0, 8) + "******");
		assertThat(saved.get("phoneMasked").asString()).isEqualTo("010-****-4444");
		assertThat(saved.toString()).doesNotContain(guarantorNo.substring(8));
		String stored = jdbc.sql("select resident_no_enc from guarantor where id = :id")
				.param("id", saved.get("id").asLong()).query(String.class).single();
		assertThat(stored).doesNotContain(guarantorNo.substring(8));

		assertThat(postJson(head, base, g).andReturn().getResponse().getStatus()).isEqualTo(409);
		g.put("residentNo", borrowerNo);
		assertThat(postJson(head, base, g).andReturn().getResponse().getStatus()).isEqualTo(422);
		assertThat(postJson(head, base + "/" + saved.get("id").asLong() + "/release", Map.of()).andReturn()
				.getResponse().getStatus()).isEqualTo(422);
		assertThat(ok(getJson(head, base))).hasSize(1);
	}
}
