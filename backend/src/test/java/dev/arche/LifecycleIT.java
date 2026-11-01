package dev.arche;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arche.support.IntegrationTest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

/**
 * 채권 생애주기 (R39~R44): 상각 후보·확정 → 매각 → 매각 후 입금 이전 → 환매, 수기 조정 결재(작성자 ≠ 승인자),
 * 상각 취소, 상계와 완제 취소(R31 해제).
 */
class LifecycleIT extends IntegrationTest {

	/**
	 * G2 조건으로 1·2회차만 납부 → 사유 발생일 07-28에 통지(R15) → 2026-08-13 기한이익상실 (업무일자 09-01).
	 * 통지는 07-28 재생으로 남겨야 상실일이 08-13이 된다 (그 뒤에 처음 재생하면 R29로 처리일이 사유 발생일)
	 */
	private long defaulted(MockHttpSession head, MockHttpSession teller, long customerId, Long staffId)
			throws Exception {
		businessDate("2026-07-28");
		long a = openLoan(head, customerId, "P3", 12_000_000, "15.0", 12, 0, 25, "2026-03-20", staffId);
		pay(teller, a, "2026-04-25", 1_177_534, tx());
		// 07-28 재생: 사유 발생 → 통지(도달 07-30, 상실 예정 08-13) 저장. 2회차는 09-01에 소급 입금해 다시 재생
		businessDate("2026-09-01");
		pay(teller, a, "2026-05-25", 1_135_616, tx());
		JsonNode d = detail(head, a);
		assertThat(d.get("status").asString()).isEqualTo("ACCELERATED");
		assertThat(d.get("acceleratedOn").asString()).isEqualTo("2026-08-13");
		return a;
	}

	private JsonNode lifecycle(MockHttpSession s, long id) throws Exception {
		return ok(getJson(s, "/api/loans/" + id + "/lifecycle"));
	}

	private boolean ledgerHas(MockHttpSession s, long id, String type) throws Exception {
		for (JsonNode e : ok(getJson(s, "/api/loans/" + id + "/ledger"))) {
			if (e.get("type").asString().equals(type)) {
				return true;
			}
		}
		return false;
	}

	@Test
	void 상각_매각_매각_후_입금_이전_환매() throws Exception {
		MockHttpSession head = login("HEAD");
		MockHttpSession teller = login("TELLER");
		MockHttpSession lead = login("COLLECTION_LEAD");
		long a = defaulted(head, teller, customer(head, "상각"), null);

		// 180일이 지나지 않으면 상각하지 않는다 (R39)
		var early = postJson(head, "/api/loans/" + a + "/charge-off", Map.of("reason", "회수 불능")).andReturn()
				.getResponse();
		assertThat(early.getStatus()).isEqualTo(422);
		assertThat(early.getContentAsString()).contains("CHARGE_OFF_INVALID");

		businessDate("2027-03-01");
		boolean candidate = false;
		for (JsonNode c : ok(getJson(head, "/api/loans/charge-off-candidates"))) {
			candidate |= c.get("accountId").asLong() == a;
		}
		assertThat(candidate).isTrue();
		// 상각·매각 확정은 본부장만 (9.1)
		assertThat(postJson(lead, "/api/loans/" + a + "/charge-off", Map.of("reason", "x")).andReturn().getResponse()
				.getStatus()).isEqualTo(403);
		ok(postJson(head, "/api/loans/" + a + "/charge-off", Map.of("reason", "회수 불능")));
		JsonNode charged = lifecycle(head, a);
		assertThat(charged.get("status").asString()).isEqualTo("CHARGED_OFF");
		assertThat(charged.get("chargedOffOn").asString()).isEqualTo("2027-03-01");
		long claimAtChargeOff = charged.get("claim").asLong();
		assertThat(detail(head, a).get("principalBalance").asLong()).isEqualTo(10_000_000);
		assertThat(ledgerHas(head, a, "CHARGE_OFF")).isTrue();

		// 양도 통지 발송일이 업무일자 뒤면 매각하지 않는다
		var noNotice = postJson(head, "/api/loans/" + a + "/sale", Map.of("buyer", "가상자산관리(가상)", "price", 1_500_000,
				"noticeSentOn", "2027-03-05", "reason", "포트폴리오 매각")).andReturn().getResponse();
		assertThat(noNotice.getStatus()).isEqualTo(422);
		ok(postJson(head, "/api/loans/" + a + "/sale", Map.of("buyer", "가상자산관리(가상)", "price", 1_500_000,
				"noticeSentOn", "2027-02-26", "reason", "포트폴리오 매각")));
		JsonNode sold = detail(head, a);
		assertThat(sold.get("status").asString()).isEqualTo("SOLD");
		assertThat(sold.get("principalBalance").asLong()).isZero();

		// 매각 후 입금은 충당하지 않고 양수인 이전 대상 (R40)
		pay(teller, a, "2027-03-01", 300_000, tx());
		JsonNode afterPay = lifecycle(head, a);
		assertThat(afterPay.get("transferPending").asLong()).isEqualTo(300_000);
		assertThat(detail(head, a).get("principalBalance").asLong()).isZero();
		assertThat(ledgerHas(head, a, "SALE_TRANSFER")).isTrue();

		// 6개월 안 환매: 잔액 복원, 매각 기간 입금은 환매일에 충당 (R41)
		businessDate("2027-03-10");
		ok(postJson(head, "/api/loans/" + a + "/repurchase", Map.of("reason", "양도 서류 누락")));
		JsonNode back = lifecycle(head, a);
		assertThat(back.get("status").asString()).isEqualTo("CHARGED_OFF");
		assertThat(back.get("transferPending").asLong()).isZero();
		assertThat(back.get("claim").asLong()).isEqualTo(claimAtChargeOff - 300_000);
		assertThat(back.get("sales").get(0).get("status").asString()).isEqualTo("REPURCHASED");
		assertThat(back.get("sales").get(0).get("repurchasePrice").asLong()).isEqualTo(1_500_000);
	}

	@Test
	void 수기_조정_결재와_상각_취소() throws Exception {
		MockHttpSession head = login("HEAD");
		MockHttpSession head2 = login("HEAD");
		MockHttpSession teller = login("TELLER");
		MockHttpSession lead = login("COLLECTION_LEAD");
		long collector = staffId(newStaff("COLLECTOR", 2, false));
		long a = defaulted(head, teller, customer(head, "조정"), collector);

		var body = new HashMap<String, Object>();
		body.put("type", "ADJUSTMENT");
		body.put("accountId", a);
		body.put("costDelta", 50_000);
		body.put("reason", "송달료 누락분 (법정 청구 가능 비용 확인)");
		long requestId = ok(postJson(lead, "/api/adjustment-requests", body)).get("id").asLong();
		// 요청만으로는 잔액이 바뀌지 않는다
		assertThat(detail(head, a).get("costBalance").asLong()).isZero();
		// 채권팀장은 승인할 수 없다
		assertThat(postJson(lead, "/api/adjustment-requests/" + requestId + "/decision", Map.of("approve", true))
				.andReturn().getResponse().getStatus()).isEqualTo(403);
		ok(postJson(head, "/api/adjustment-requests/" + requestId + "/decision", Map.of("approve", true)));
		assertThat(detail(head, a).get("costBalance").asLong()).isEqualTo(50_000);
		assertThat(ledgerHas(head, a, "ADJUSTMENT")).isTrue();

		// 본인 요청은 본인이 승인할 수 없다
		long own = ok(postJson(head, "/api/adjustment-requests",
				Map.of("type", "ADJUSTMENT", "accountId", a, "lateDown", 1_000, "reason", "가산이자 일부 감액")))
				.get("id").asLong();
		var same = postJson(head, "/api/adjustment-requests/" + own + "/decision", Map.of("approve", true))
				.andReturn().getResponse();
		assertThat(same.getStatus()).isEqualTo(422);
		assertThat(same.getContentAsString()).contains("SAME_APPROVER");
		ok(postJson(head2, "/api/adjustment-requests/" + own + "/decision", Map.of("approve", false)));

		// 상각 → 상각 취소 (R43): 상각 거래 역분개, 상태 복귀
		businessDate("2027-03-01");
		ok(postJson(head, "/api/loans/" + a + "/charge-off", Map.of("reason", "회수 불능")));
		long revert = ok(postJson(lead, "/api/adjustment-requests",
				Map.of("type", "REVERT_CHARGE_OFF", "accountId", a, "reason", "상각 결재 오류"))).get("id").asLong();
		ok(postJson(head, "/api/adjustment-requests/" + revert + "/decision", Map.of("approve", true)));
		JsonNode reverted = lifecycle(head, a);
		assertThat(reverted.get("status").asString()).isEqualTo("ACCELERATED");
		assertThat(reverted.get("chargedOffOn").isNull()).isTrue();
		assertThat(ledgerHas(head, a, "REVERSAL")).isTrue();
	}

	@Test
	void 상계와_완제_취소() throws Exception {
		MockHttpSession head = login("HEAD");
		MockHttpSession teller = login("TELLER");
		MockHttpSession lead = login("COLLECTION_LEAD");
		long collector = staffId(newStaff("COLLECTOR", 2, false));
		long customer = customer(head, "상계");
		long y = defaulted(head, teller, customer, collector);
		long x = openLoan(head, customer, "P5", 1_000_000, "19.5", 12, 0, 20, "2026-08-01", collector);
		long total = ok(getJson(head, "/api/loans/" + x + "/quote")).get("total").asLong();
		long paymentId = pay(teller, x, "2026-09-01", total + 50_000, tx()).get("id").asLong();
		assertThat(detail(head, x).get("status").asString()).isEqualTo("PAID_OFF");
		assertThat(detail(head, x).get("refundPending").asLong()).isEqualTo(50_000);
		String xNo = detail(head, x).get("accountNo").asString();

		// 상계: X의 반환 대기 50,000원을 Y의 채무에 충당 (R44)
		var setoff = new HashMap<String, Object>();
		setoff.put("type", "SETOFF");
		setoff.put("accountId", y);
		setoff.put("sourceAccountNo", xNo);
		setoff.put("setoffSource", "REFUND");
		setoff.put("amount", 50_000);
		setoff.put("reason", "반환 대기 과입금 상계 (채무자 통지)");
		long req = ok(postJson(lead, "/api/adjustment-requests", setoff)).get("id").asLong();
		ok(postJson(head, "/api/adjustment-requests/" + req + "/decision", Map.of("approve", true)));
		assertThat(lifecycle(head, x).get("refundPending").asLong()).isZero();
		assertThat(ledgerHas(head, y, "SETOFF")).isTrue();
		long setoffInEvent = lifecycle(head, y).get("setoffs").get(0).get("eventId").asLong();

		// 완제 계약의 입금은 취소할 수 없다 (R31)
		var cancel = Map.of("type", "CANCEL", "paymentId", paymentId, "reason", "부도 수표");
		var blocked = postJson(lead, "/api/payment-corrections", cancel).andReturn().getResponse();
		assertThat(blocked.getStatus()).isEqualTo(422);
		assertThat(blocked.getContentAsString()).contains("CANCEL_AFTER_PAYOFF");

		// 완제 취소 승인 (R43) → 그래도 상계에 쓴 돈이 모자라게 되면 거절 (R44)
		long unlock = ok(postJson(lead, "/api/adjustment-requests",
				Map.of("type", "REVERT_PAYOFF", "accountId", x, "reason", "부도 수표"))).get("id").asLong();
		ok(postJson(head, "/api/adjustment-requests/" + unlock + "/decision", Map.of("approve", true)));
		var shortage = postJson(lead, "/api/payment-corrections", cancel).andReturn().getResponse();
		assertThat(shortage.getStatus()).isEqualTo(422);
		assertThat(shortage.getContentAsString()).contains("SETOFF_SHORTAGE");

		// 상계 취소 후에는 입금 취소가 된다
		long revertSetoff = ok(postJson(lead, "/api/adjustment-requests", Map.of("type", "REVERT_SETOFF", "accountId", y,
				"targetEventId", setoffInEvent, "reason", "원천 입금 부도"))).get("id").asLong();
		ok(postJson(head, "/api/adjustment-requests/" + revertSetoff + "/decision", Map.of("approve", true)));
		ok(postJson(lead, "/api/payment-corrections", cancel));
		assertThat(detail(head, x).get("status").asString()).isNotEqualTo("PAID_OFF");
		boolean consumed = false;
		for (JsonNode r : lifecycle(head, x).get("requests")) {
			consumed |= r.get("id").asLong() == unlock && r.get("status").asString().equals("CONSUMED");
		}
		assertThat(consumed).isTrue();
	}
}
