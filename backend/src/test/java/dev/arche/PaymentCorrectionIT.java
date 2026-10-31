package dev.arche;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arche.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

/**
 * G11 (미확인 입금 소급 매칭) → 일마감 3회 → G27 (마감 후 취소, R28 2단계) → G28 (다른 계약으로 정정 이동).
 * 화면이 쓰는 REST API 그대로 검증한다.
 */
class PaymentCorrectionIT extends IntegrationTest {

	@Test
	void G11_G27_G28_소급_매칭_마감_후_취소_정정_이동() throws Exception {
		businessDate("2026-06-12");
		MockHttpSession head = login("HEAD");
		MockHttpSession teller = login("TELLER");
		MockHttpSession admin = login("SYSTEM_ADMIN");
		MockHttpSession lead = login("COLLECTION_LEAD");

		long a = openLoan(head, customer(head, "계약A"), "P3", 12_000_000, "15.0", 12, 0, 25, "2026-03-20", null);
		long b = openLoan(head, customer(head, "계약B"), "P5", 3_000_000, "19.5", 12, 0, 20, "2026-04-20", null);
		pay(teller, a, "2026-04-25", 1_177_534, tx());
		pay(teller, b, "2026-05-20", 48_082, tx());
		long paymentId = pay(teller, null, "2026-05-26", 1_135_616, tx()).get("id").asLong();

		// 매칭 전: G7·G11 매칭 전 열
		JsonNode before = detail(head, a);
		assertThat(before.get("status").asString()).isEqualTo("DELINQUENT");
		assertThat(before.get("delinquencyDays").asInt()).isEqualTo(18);
		assertThat(before.get("principalBalance").asLong()).isEqualTo(11_000_000);
		assertThat(before.get("outstandingNormalInterest").asLong()).isEqualTo(216_986);
		assertThat(before.get("outstandingLateInterest").asLong()).isEqualTo(1_479);

		// G11: 06-12에 미확인 입금을 A에 매칭 (기산일 05-26 → 약정일 05-25)
		JsonNode open = ok(getJson(teller, "/api/unmatched-deposits"));
		long unmatchedId = -1;
		for (JsonNode u : open) {
			if (u.get("paymentId").asLong() == paymentId) {
				unmatchedId = u.get("id").asLong();
			}
		}
		ok(postJson(teller, "/api/unmatched-deposits/" + unmatchedId + "/match", Map.of("accountId", a)));
		JsonNode matched = detail(head, a);
		assertThat(matched.get("status").asString()).isEqualTo("NORMAL");
		assertThat(matched.get("delinquencyDays").asInt()).isZero();
		assertThat(matched.get("principalBalance").asLong()).isEqualTo(10_000_000);
		assertThat(matched.get("outstandingNormalInterest").asLong()).isEqualTo(73_972);
		assertThat(matched.get("outstandingLateInterest").asLong()).isZero();

		// 일마감 3회: 06-12 → 06-15
		for (int i = 0; i < 3; i++) {
			ok(postJson(admin, "/api/operation/closings", Map.of()));
		}
		assertThat(ok(getJson(head, "/api/operation/business-date")).get("businessDate").asString())
				.isEqualTo("2026-06-15");

		// G27: 마감 후 취소 → 시스템관리자 요청, 채권팀장 승인 (R28)
		JsonNode requested = ok(postJson(admin, "/api/payment-corrections",
				Map.of("type", "CANCEL", "paymentId", paymentId, "reason", "다른 고객의 입금")));
		assertThat(requested.get("status").asString()).isEqualTo("REQUESTED");
		assertThat(requested.get("afterClose").asBoolean()).isTrue();
		assertThat(postJson(teller, "/api/payment-corrections/" + requested.get("id").asLong() + "/approval",
				Map.of("approve", true)).andReturn().getResponse().getStatus()).isEqualTo(403);
		ok(postJson(lead, "/api/payment-corrections/" + requested.get("id").asLong() + "/approval",
				Map.of("approve", true)));

		JsonNode cancelled = detail(head, a);
		assertThat(cancelled.get("status").asString()).isEqualTo("DELINQUENT");
		assertThat(cancelled.get("delinquencyDays").asInt()).isEqualTo(21);
		assertThat(cancelled.get("principalBalance").asLong()).isEqualTo(11_000_000);
		assertThat(cancelled.get("lastSettledOn").asString()).isEqualTo("2026-04-25");
		assertThat(cancelled.get("outstandingNormalInterest").asLong()).isEqualTo(230_547);
		assertThat(cancelled.get("outstandingLateInterest").asLong()).isEqualTo(1_726);

		// 원장은 지우지 않고 역분개로 남는다 (R26)
		JsonNode ledger = ok(getJson(head, "/api/loans/" + a + "/ledger"));
		long reversals = 0;
		long superseded = 0;
		for (JsonNode e : ledger) {
			reversals += e.get("type").asString().equals("REVERSAL") ? 1 : 0;
			superseded += e.get("superseded").asBoolean() ? 1 : 0;
		}
		assertThat(reversals).isPositive().isEqualTo(superseded);

		// 취소된 돈은 미확인 입금으로 돌아간다
		boolean reopened = false;
		for (JsonNode u : ok(getJson(teller, "/api/unmatched-deposits"))) {
			reopened |= u.get("paymentId").asLong() == paymentId && u.get("amount").asLong() == 1_135_616;
		}
		assertThat(reopened).isTrue();

		// 06-15 일보의 정정 항목 (R28): 마감 후 마감
		JsonNode closing = ok(postJson(admin, "/api/operation/closings", Map.of()));
		assertThat(closing.get("correctionTotal").asLong()).isEqualTo(-1_135_616);
		assertThat(closing.get("mismatches").asInt()).isZero();

		// G28: 같은 입금을 A에 다시 매칭한 뒤, 실제 주인인 B로 정정 이동 (06-16, 마감 후 정정)
		long reopenedId = -1;
		for (JsonNode u : ok(getJson(teller, "/api/unmatched-deposits"))) {
			if (u.get("paymentId").asLong() == paymentId) {
				reopenedId = u.get("id").asLong();
			}
		}
		ok(postJson(teller, "/api/unmatched-deposits/" + reopenedId + "/match", Map.of("accountId", a)));
		JsonNode move = ok(postJson(admin, "/api/payment-corrections",
				Map.of("type", "MOVE", "paymentId", paymentId, "targetAccountId", b, "reason", "실제 주인은 B")));
		ok(postJson(lead, "/api/payment-corrections/" + move.get("id").asLong() + "/approval",
				Map.of("approve", true)));

		JsonNode accountB = detail(head, b);
		assertThat(accountB.get("principalBalance").asLong()).isEqualTo(1_874_000);
		assertThat(accountB.get("lastSettledOn").asString()).isEqualTo("2026-05-26");
		JsonNode bLedger = ok(getJson(head, "/api/loans/" + b + "/ledger"));
		JsonNode moved = null;
		for (JsonNode e : bLedger) {
			if (e.get("paymentId") != null && !e.get("paymentId").isNull()
					&& e.get("paymentId").asLong() == paymentId) {
				moved = e;
			}
		}
		assertThat(moved).isNotNull();
		assertThat(moved.get("normalInterest").asLong()).isEqualTo(9_616);
		assertThat(moved.get("principal").asLong()).isEqualTo(1_126_000);
		assertThat(moved.get("correctionId").isNull()).isFalse();
		assertThat(detail(head, a).get("status").asString()).isEqualTo("DELINQUENT");
	}

	@Test
	void G26_금액_오입력_당일_정정은_처리한_본인이_즉시_처리한다() throws Exception {
		businessDate("2026-05-11");
		MockHttpSession head = login("HEAD");
		String collectorId = newStaff("COLLECTOR", 2, false);
		MockHttpSession collector = loginAs(collectorId);
		long d = openLoan(head, customer(head, "G15"), "P5", 10_000_000, "19.5", 12, 0, 20, "2026-02-20",
				staffId(collectorId));
		pay(collector, d, "2026-03-20", 149_589, tx());
		pay(collector, d, "2026-04-20", 2_000_000, tx());
		long wrong = pay(collector, d, "2026-05-11", 1_000_000, tx()).get("id").asLong();
		assertThat(detail(head, d).get("principalBalance").asLong()).isEqualTo(7_257_227);

		JsonNode fix = ok(postJson(collector, "/api/payment-corrections",
				Map.of("type", "AMOUNT_FIX", "paymentId", wrong, "correctedAmount", 100_000, "reason", "금액 오입력")));
		assertThat(fix.get("status").asString()).isEqualTo("APPLIED");
		assertThat(fix.get("afterClose").asBoolean()).isFalse();

		JsonNode fixed = detail(head, d);
		assertThat(fixed.get("principalBalance").asLong()).isEqualTo(8_157_227);
		// 실제로 들어오지 않은 900,000원은 미확인 입금으로 남지 않는다 (R26)
		for (JsonNode u : ok(getJson(head, "/api/unmatched-deposits"))) {
			assertThat(u.get("paymentId").asLong()).isNotEqualTo(wrong);
		}
		businessDate("2026-05-20");
		assertThat(ok(getJson(head, "/api/loans/" + d + "/quote")).get("normalInterest").asLong())
				.isEqualTo(39_221);
	}

	@Test
	void 당일_취소는_처리한_본인_또는_채권팀장만() throws Exception {
		businessDate("2026-06-12");
		MockHttpSession head = login("HEAD");
		MockHttpSession teller = login("TELLER");
		MockHttpSession otherTeller = login("TELLER");
		long a = openLoan(head, customer(head, "취소권한"), "P3", 12_000_000, "15.0", 12, 0, 25, "2026-03-20", null);
		long p = pay(teller, a, "2026-04-25", 1_177_534, tx()).get("id").asLong();

		var denied = postJson(otherTeller, "/api/payment-corrections",
				Map.of("type", "CANCEL", "paymentId", p, "reason", "남의 입금 취소 시도"));
		assertThat(denied.andReturn().getResponse().getStatus()).isEqualTo(403);

		JsonNode ok = ok(postJson(teller, "/api/payment-corrections",
				Map.of("type", "CANCEL", "paymentId", p, "reason", "중복 등록")));
		assertThat(ok.get("status").asString()).isEqualTo("APPLIED");
	}
}
