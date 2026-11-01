package dev.arche;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.arche.support.IntegrationTest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

/**
 * R47·R48 일괄 입금 배분, R49·R50 보증인·제3자 입금과 대위변제 기록.
 */
class PaymentSplitIT extends IntegrationTest {

	@Test
	void 한_고객의_두_계약에_나눠_충당하고_원_입금_단위로_취소한_뒤_다시_배분한다() throws Exception {
		businessDate("2026-06-12");
		MockHttpSession head = login("HEAD");
		MockHttpSession teller = login("TELLER");
		long customer = customer(head, "배분고객");
		long a1 = acquire(head, customer, "2026-06-01", "2026-06-01");
		long a2 = acquire(head, customer, "2026-06-05", "2026-06-05");
		String a1No = detail(head, a1).get("accountNo").asString();

		JsonNode targets = ok(mvc.perform(get("/api/payment-splits/targets").param("accountNo", a1No).session(teller)));
		assertThat(targets.get("customerId").asLong()).isEqualTo(customer);
		assertThat(targets.get("accounts")).hasSize(2);
		for (JsonNode t : targets.get("accounts")) {
			// 매입채권은 연체금 = 완제 금액 (R47)
			assertThat(t.get("overdue").asLong()).isEqualTo(t.get("payoff").asLong()).isPositive();
		}

		// 수동 지정
		JsonNode split = ok(postJson(teller, "/api/payment-splits", Map.of("customerId", customer, "method", "MANUAL",
				"bankDate", "2026-06-12", "amount", 300_000, "depositorName", "배분고객", "manual",
				List.of(Map.of("accountId", a1, "amount", 100_000), Map.of("accountId", a2, "amount", 200_000)))));
		long parentId = split.get("parent").get("id").asLong();
		assertThat(split.get("parent").get("status").asString()).isEqualTo("SPLIT");
		assertThat(split.get("parts")).hasSize(2);
		assertThat(detail(head, a1).get("totalPaid").asLong()).isEqualTo(100_000);
		assertThat(detail(head, a2).get("totalPaid").asLong()).isEqualTo(200_000);

		JsonNode a1Payments = ok(mvc.perform(get("/api/payments").param("accountId", String.valueOf(a1)).session(teller)));
		assertThat(a1Payments).hasSize(1);
		assertThat(a1Payments.get(0).get("parentPaymentId").asLong()).isEqualTo(parentId);
		long childId = a1Payments.get(0).get("id").asLong();

		// 하위 입금은 따로 취소할 수 없다 (R48)
		assertThat(postJson(teller, "/api/payment-corrections",
				Map.of("type", "CANCEL", "paymentId", childId, "reason", "개별 취소 시도")).andReturn().getResponse()
				.getStatus()).isEqualTo(422);
		// 원 입금은 전체 취소만
		assertThat(postJson(teller, "/api/payment-corrections",
				Map.of("type", "AMOUNT_FIX", "paymentId", parentId, "correctedAmount", 1, "reason", "정정 시도"))
				.andReturn().getResponse().getStatus()).isEqualTo(422);

		JsonNode cancel = ok(postJson(teller, "/api/payment-corrections",
				Map.of("type", "CANCEL", "paymentId", parentId, "reason", "배분 비율 변경")));
		assertThat(cancel.get("status").asString()).isEqualTo("APPLIED");
		assertThat(detail(head, a1).get("totalPaid").asLong()).isZero();
		assertThat(detail(head, a2).get("totalPaid").asLong()).isZero();

		long unmatchedId = -1;
		for (JsonNode u : ok(getJson(teller, "/api/unmatched-deposits"))) {
			if (u.get("paymentId").asLong() == parentId) {
				unmatchedId = u.get("id").asLong();
				assertThat(u.get("amount").asLong()).isEqualTo(300_000);
			}
		}
		assertThat(unmatchedId).isPositive();

		// 미확인 입금을 잔액 비례로 다시 배분
		JsonNode resplit = ok(postJson(teller, "/api/payment-splits",
				Map.of("customerId", customer, "method", "PROPORTIONAL", "unmatchedId", unmatchedId)));
		long sum = 0;
		for (JsonNode p : resplit.get("parts")) {
			sum += p.get("amount").asLong();
			assertThat(p.get("bankTxId").asString()).matches(".*-S[34]$");
		}
		assertThat(sum).isEqualTo(300_000);
		assertThat(detail(head, a1).get("totalPaid").asLong() + detail(head, a2).get("totalPaid").asLong())
				.isEqualTo(300_000);
	}

	@Test
	void 보증인_입금은_대위변제로_기록하고_소멸시효를_중단하지_않는다() throws Exception {
		businessDate("2026-08-13");
		MockHttpSession head = login("HEAD");
		long account = acquire(head, customer(head, "보증대상"), "2026-08-13", "2026-08-13");

		businessDate("2027-03-02");
		MockHttpSession teller = login("TELLER");
		var missingName = new HashMap<String, Object>(Map.of("accountId", account, "bankDate", "2027-03-02",
				"amount", 10_000, "payerType", "GUARANTOR"));
		assertThat(postJson(teller, "/api/payments", missingName).andReturn().getResponse().getStatus())
				.isEqualTo(422);
		var courtByGuarantor = new HashMap<String, Object>(Map.of("accountId", account, "bankDate", "2027-03-02",
				"amount", 10_000, "payerType", "GUARANTOR", "payerName", "김보증", "channel", "AUCTION_DISTRIBUTION"));
		assertThat(postJson(teller, "/api/payments", courtByGuarantor).andReturn().getResponse().getStatus())
				.isEqualTo(422);

		long paymentId = ok(postJson(teller, "/api/payments", Map.of("accountId", account, "bankDate", "2027-03-02",
				"amount", 10_000, "payerType", "GUARANTOR", "payerName", "김보증", "payerRef", "연대보증 약정")))
				.get("id").asLong();
		assertThat(detail(head, account).get("totalPaid").asLong()).isEqualTo(10_000);

		JsonNode limitation = ok(getJson(head, "/api/loans/" + account + "/limitation"));
		assertThat(limitation.get("completionDate").asString()).isEqualTo("2031-08-13");
		boolean shown = false;
		for (JsonNode e : limitation.get("events")) {
			if (e.get("type").asString().equals("PAYMENT")) {
				assertThat(e.get("counted").asBoolean()).isFalse();
				shown = true;
			}
		}
		assertThat(shown).isTrue();

		JsonNode claims = ok(mvc.perform(get("/api/subrogations").param("accountId", String.valueOf(account))
				.session(head)));
		assertThat(claims).hasSize(1);
		assertThat(claims.get(0).get("status").asString()).isEqualTo("ACTIVE");
		assertThat(claims.get(0).get("amount").asLong()).isEqualTo(10_000);
		assertThat(claims.get(0).get("payerNameMasked").asString()).isEqualTo("김*증");
		assertThat(claims.get(0).get("payerType").asString()).isEqualTo("GUARANTOR");

		JsonNode row = ok(mvc.perform(get("/api/payments").param("accountId", String.valueOf(account)).session(head)))
				.get(0);
		assertThat(row.get("payerType").asString()).isEqualTo("GUARANTOR");
		assertThat(row.get("payerNameMasked").asString()).isEqualTo("김*증");

		// 입금 취소 → 대위변제 기록도 취소 (R49)
		ok(postJson(teller, "/api/payment-corrections",
				Map.of("type", "CANCEL", "paymentId", paymentId, "reason", "보증인 입금 반환 요청")));
		JsonNode after = ok(mvc.perform(get("/api/subrogations").param("accountId", String.valueOf(account))
				.session(head)));
		assertThat(after.get(0).get("status").asString()).isEqualTo("CANCELLED");

		// 본인 입금은 중단 사유
		pay(teller, account, "2027-03-02", 10_000, tx());
		assertThat(ok(getJson(head, "/api/loans/" + account + "/limitation")).get("completionDate").asString())
				.isEqualTo("2032-03-02");
	}

	private long acquire(MockHttpSession head, long customerId, String acquiredOn, String limitationStart)
			throws Exception {
		var body = new HashMap<String, Object>();
		body.put("customerId", customerId);
		body.put("acquiredOn", acquiredOn);
		body.put("acquisitionPrice", 800_000);
		body.put("seller", "가상저축은행(가상)");
		body.put("originalContractDate", "2020-01-01");
		body.put("originalRate", "16.0");
		body.put("originalLateRate", "19.0");
		body.put("principal", 4_000_000);
		body.put("preAcqInterest", 1_200_000);
		body.put("cost", 150_000);
		body.put("interestCharged", true);
		body.put("limitationStartDate", limitationStart);
		return ok(postJson(head, "/api/loans/acquisitions", body)).get("account").get("id").asLong();
	}
}
