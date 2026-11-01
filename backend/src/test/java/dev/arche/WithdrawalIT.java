package dev.arche;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arche.support.IntegrationTest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

/**
 * 출금 작성·승인·송금 (guide 6.4, 정책서 7장·9.1, R62~R64): 작성자 ≠ 승인자, 1,000만원 초과 2단계, 금액 예약과 해제,
 * 송금 타임아웃 → 결과 조회.
 */
class WithdrawalIT extends IntegrationTest {

	@Test
	void 미확인_입금_반환은_예약_중_매칭을_막고_승인되면_송금된다() throws Exception {
		businessDate("2026-06-12");
		MockHttpSession head = login("HEAD");
		String tellerId = newStaff("TELLER", 9, false);
		MockHttpSession teller = loginAs(tellerId);
		MockHttpSession lead = login("COLLECTION_LEAD");
		String depositor = "반환자" + (System.nanoTime() % 1000);
		var deposit = new HashMap<String, Object>(Map.of("bankDate", "2026-06-12", "amount", 3_000_000, "bankTxId",
				tx(), "depositorName", depositor));
		long paymentId = ok(postJson(teller, "/api/payments", deposit)).get("id").asLong();
		long unmatchedId = -1;
		for (JsonNode u : ok(getJson(teller, "/api/withdrawals/candidates")).get("unmatched")) {
			if (u.get("paymentId").asLong() == paymentId) {
				unmatchedId = u.get("unmatchedId").asLong();
			}
		}
		assertThat(unmatchedId).isPositive();

		Map<String, Object> body = new HashMap<>(Map.of("type", "UNMATCHED_REFUND", "unmatchedId", unmatchedId,
				"amount", 3_000_000, "bankName", "가상은행", "accountNo", "110-123-4561", "holderName", "다른사람",
				"reason", "착오 입금 반환"));
		assertThat(status(postJson(teller, "/api/withdrawals", body))).isEqualTo(422); // 입금자 본인 아님
		body.put("holderName", depositor);
		body.put("amount", 1_000_000);
		assertThat(status(postJson(teller, "/api/withdrawals", body))).isEqualTo(422); // 전액만
		body.put("amount", 3_000_000);
		assertThat(status(postJson(lead, "/api/withdrawals", body))).isEqualTo(403); // 작성은 출납만
		JsonNode w = ok(postJson(teller, "/api/withdrawals", body));
		assertThat(w.get("status").asString()).isEqualTo("REQUESTED");
		assertThat(w.get("payeeAccountMasked").asString()).isEqualTo("******4561");
		assertThat(w.get("twoStep").asBoolean()).isFalse();
		long id = w.get("id").asLong();

		// 예약 중에는 계약에 매칭할 수 없다
		long loan = openLoan(head, customer(head, "매칭"), "P5", 5_000_000, "19.5", 12, 0, 20, "2026-06-01", null);
		assertThat(status(postJson(teller, "/api/unmatched-deposits/" + unmatchedId + "/match",
				Map.of("accountId", loan)))).isEqualTo(422);
		assertThat(status(postJson(teller, "/api/withdrawals", body))).isIn(409, 422); // 같은 입금 두 번 반환 불가

		JsonNode done = ok(postJson(lead, "/api/withdrawals/" + id + "/approve", Map.of()));
		assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
		assertThat(done.get("resultCode").asString()).isEqualTo("OK");
		assertThat(jdbc.sql("select status from unmatched_deposit where id = :id").param("id", unmatchedId)
				.query(String.class).single()).isEqualTo("REFUNDED");
		long logs = jdbc.sql("select count(*) from integration_call_log where institution = '가상 펌뱅킹'"
				+ " and summary like '%4561%' and summary not like '%110-123%'").query(Long.class).single();
		assertThat(logs).isPositive();
	}

	@Test
	void 천만원_초과_과입금_반환은_팀장_본부장_2단계이고_타임아웃은_결과_조회로_확정한다() throws Exception {
		businessDate("2026-06-12");
		MockHttpSession head = login("HEAD");
		MockHttpSession teller = login("TELLER");
		MockHttpSession lead = login("COLLECTION_LEAD");
		String name = "과입금" + (System.nanoTime() % 1000);
		long loan = openLoan(head, customer(head, name), "P5", 3_000_000, "19.5", 12, 0, 20, "2026-06-01", null);
		long payoff = ok(getJson(head, "/api/loans/" + loan + "/quote")).get("total").asLong();
		pay(teller, loan, "2026-06-12", payoff + 15_000_000, tx());
		assertThat(detail(head, loan).get("refundPending").asLong()).isEqualTo(15_000_000);

		Map<String, Object> body = new HashMap<>(Map.of("type", "OVERPAYMENT_REFUND", "accountId", loan, "amount",
				12_000_000, "bankName", "가상은행", "accountNo", "220-555-0008", "holderName", name, "reason",
				"완제 후 과입금 반환"));
		JsonNode w = ok(postJson(teller, "/api/withdrawals", body));
		long id = w.get("id").asLong();
		assertThat(w.get("twoStep").asBoolean()).isTrue();
		assertThat(available(teller, loan)).isEqualTo(3_000_000);
		// 반환 가능액을 넘는 두 번째 요청은 거절
		body.put("amount", 3_000_001);
		assertThat(status(postJson(teller, "/api/withdrawals", body))).isEqualTo(422);

		assertThat(status(postJson(teller, "/api/withdrawals/" + id + "/approve", Map.of()))).isEqualTo(403);
		assertThat(status(postJson(head, "/api/withdrawals/" + id + "/approve", Map.of()))).isEqualTo(403); // 1단계는 팀장
		JsonNode first = ok(postJson(lead, "/api/withdrawals/" + id + "/approve", Map.of()));
		assertThat(first.get("status").asString()).isEqualTo("FIRST_APPROVED");
		assertThat(status(postJson(lead, "/api/withdrawals/" + id + "/approve", Map.of()))).isEqualTo(403);

		// 계좌 끝자리 8: 타임아웃 → UNKNOWN, 결과 조회로 성공 확정
		JsonNode sent = ok(postJson(head, "/api/withdrawals/" + id + "/approve", Map.of()));
		assertThat(sent.get("status").asString()).isEqualTo("UNKNOWN");
		JsonNode inquired = ok(postJson(teller, "/api/withdrawals/" + id + "/inquire", Map.of()));
		assertThat(inquired.get("status").asString()).isEqualTo("COMPLETED");
		assertThat(inquired.get("inquiryCount").asInt()).isEqualTo(1);
		assertThat(available(teller, loan)).isEqualTo(3_000_000);

		// 끝자리 9: 송금 실패 → 예약 해제
		body.put("amount", 3_000_000);
		body.put("accountNo", "220-555-0009");
		long failing = ok(postJson(teller, "/api/withdrawals", body)).get("id").asLong();
		assertThat(available(teller, loan)).isZero();
		assertThat(ok(postJson(lead, "/api/withdrawals/" + failing + "/approve", Map.of())).get("status").asString())
				.isEqualTo("FAILED");
		assertThat(available(teller, loan)).isEqualTo(3_000_000);

		// 반려도 예약을 되돌린다
		body.put("accountNo", "220-555-0001");
		long rejected = ok(postJson(teller, "/api/withdrawals", body)).get("id").asLong();
		assertThat(status(postJson(lead, "/api/withdrawals/" + rejected + "/reject", Map.of("reason", "")))).isEqualTo(400);
		assertThat(ok(postJson(lead, "/api/withdrawals/" + rejected + "/reject", Map.of("reason", "계좌 재확인")))
				.get("status").asString()).isEqualTo("REJECTED");
		assertThat(available(teller, loan)).isEqualTo(3_000_000);
		long reserved = jdbc.sql("select coalesce(sum(amount), 0) from refund_payout where account_id = :a")
				.param("a", loan).query(Long.class).single();
		assertThat(reserved).isEqualTo(12_000_000);
	}

	@Test
	void 예금주_불일치와_계약_중_가수금_반환은_작성할_수_없다() throws Exception {
		businessDate("2026-06-12");
		MockHttpSession head = login("HEAD");
		MockHttpSession teller = login("TELLER");
		String name = "가수금" + (System.nanoTime() % 1000);
		long loan = openLoan(head, customer(head, name), "P1", 5_000_000, "19.9", 12, 0, 10, "2026-06-01", null);
		pay(teller, loan, "2026-06-12", 100_000, tx());
		Map<String, Object> body = new HashMap<>(Map.of("type", "OVERPAYMENT_REFUND", "accountId", loan, "amount",
				100_000, "bankName", "가상은행", "accountNo", "330-555-0001", "holderName", name, "reason", "가수금 반환"));
		assertThat(status(postJson(teller, "/api/withdrawals", body))).isEqualTo(422);
		body.put("accountNo", "330-555-0006");
		JsonNode mismatch = body(postJson(teller, "/api/withdrawals", body));
		assertThat(mismatch.get("code").asString()).isEqualTo("HOLDER_MISMATCH");
	}

	private long available(MockHttpSession teller, long loan) throws Exception {
		for (JsonNode r : ok(getJson(teller, "/api/withdrawals/candidates")).get("refunds")) {
			if (r.get("accountId").asLong() == loan) {
				return r.get("available").asLong();
			}
		}
		return 0;
	}

	private static int status(org.springframework.test.web.servlet.ResultActions r) {
		return r.andReturn().getResponse().getStatus();
	}
}
