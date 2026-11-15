package dev.arche;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.arche.support.IntegrationTest;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

/**
 * 일마감 계약 단위 실패 격리 (R76, ADR 0012). 다른 통합 테스트와 겹치지 않는 2027-10-15에 마감한다.
 * <p>
 * "잘못 입력된 양수 데이터": 매입채권의 입금 이벤트 날짜를 SQL로 양수일보다 앞으로 옮겨, 입력 단계 검증(PAYMENT_DATE_INVALID)을
 * 거치지 않은 데이터를 만든다. 같은 조건·같은 입금의 매입채권 하나를 대조군으로 둔다.
 */
class DailyClosingIT extends IntegrationTest {

	private static final String D = "2027-10-15";
	private static final String NEXT = "2027-10-16";

	record AccountRow(String status, LocalDate asOf, long principalBalance, long payoffAmount, long suspenseBalance) {
	}

	private AccountRow row(long accountId) {
		return jdbc.sql("select status, as_of, principal_balance, payoff_amount, suspense_balance from loan_account"
				+ " where id = :id").param("id", accountId)
				.query((rs, n) -> new AccountRow(rs.getString("status"), rs.getObject("as_of", LocalDate.class),
						rs.getLong("principal_balance"), rs.getLong("payoff_amount"), rs.getLong("suspense_balance")))
				.single();
	}

	private long ledgerRows(long accountId) {
		return jdbc.sql("select count(*) from ledger_entry where account_id = :id").param("id", accountId)
				.query(Long.class).single();
	}

	private long acquire(MockHttpSession head, String name) throws Exception {
		var body = new HashMap<String, Object>();
		body.put("customerId", customer(head, name));
		body.put("acquiredOn", "2027-09-01");
		body.put("acquisitionPrice", 800_000);
		body.put("seller", "가상저축은행(가상)");
		body.put("originalContractDate", "2022-01-01");
		body.put("originalRate", "16.0");
		body.put("originalLateRate", "19.0");
		body.put("principal", 4_000_000);
		body.put("preAcqInterest", 1_200_000);
		body.put("cost", 150_000);
		body.put("interestCharged", true);
		body.put("limitationStartDate", "2027-09-01");
		return ok(postJson(head, "/api/loans/acquisitions", body)).get("account").get("id").asLong();
	}

	private JsonNode failureOf(MockHttpSession session, long accountId) throws Exception {
		for (JsonNode f : ok(getJson(session, "/api/operation/closings/" + D + "/failures"))) {
			if (f.get("accountId").asLong() == accountId) {
				return f;
			}
		}
		throw new AssertionError("실패 기록 없음: 계약 " + accountId);
	}

	@Test
	void R76_잘못_입력된_양수_데이터가_있어도_마감이_끝나고_고친_뒤_재판정하면_같은_기준으로_맞춰진다() throws Exception {
		businessDate(D);
		MockHttpSession head = login("HEAD");
		MockHttpSession admin = login("SYSTEM_ADMIN");

		// 정상 대출 (첫 약정일 09-10 미납 → 연체), 대조군 매입채권, 오입력 매입채권 (둘 다 09-05에 10만원 입금)
		long normal = openLoan(head, customer(head, "정상대출"), "P1", 3_000_000, "19.9", 12, 0, 10, "2027-08-01",
				null);
		long control = acquire(head, "대조양수");
		long broken = acquire(head, "오입력양수");
		pay(head, control, "2027-09-05", 100_000, tx());
		pay(head, broken, "2027-09-05", 100_000, tx());

		// 이관 오류 재현: 입금 이벤트 날짜를 양수일(09-01)보다 앞으로 (입력 검증 우회)
		int moved = jdbc.sql("update account_event set event_date = '2027-08-20' where account_id = :a"
				+ " and type = 'PAYMENT'").param("a", broken).update();
		assertThat(moved).isEqualTo(1);
		AccountRow brokenBefore = row(broken);
		long brokenLedgerBefore = ledgerRows(broken);

		// 일마감: 오입력 계약만 실패하고 마감·업무일자 변경까지 진행
		JsonNode closing = ok(postJson(admin, "/api/operation/closings", Map.of()));
		assertThat(closing.get("judgeFailures").asInt()).isGreaterThanOrEqualTo(1);
		assertThat(closing.get("nextBusinessDate").asString()).isEqualTo(NEXT);
		assertThat(ok(getJson(head, "/api/operation/business-date")).get("businessDate").asString())
				.isEqualTo(NEXT);

		// 실패 기록: 그 밖의 예외(SYSTEM)로, 미해결
		JsonNode failure = failureOf(admin, broken);
		assertThat(failure.get("kind").asString()).isEqualTo("SYSTEM");
		assertThat(failure.get("code").asString()).isEqualTo("IllegalStateException");
		assertThat(failure.get("message").asString()).contains("2027-08-20").contains("2027-09-01");
		assertThat(failure.get("resolved").asBoolean()).isFalse();
		assertThat(failure.get("accountNo").asString()).isEqualTo(detail(head, broken).get("accountNo").asString());

		// 실패한 계약은 롤백: 마감 전 그대로 (asOf·잔액·원장)
		assertThat(row(broken)).isEqualTo(brokenBefore);
		assertThat(row(broken).asOf()).isEqualTo(LocalDate.parse(D));
		assertThat(ledgerRows(broken)).isEqualTo(brokenLedgerBefore);

		// 다른 계약은 D+1로 정상 판정
		assertThat(row(normal).asOf()).isEqualTo(LocalDate.parse(NEXT));
		assertThat(row(normal).status()).isEqualTo("DELINQUENT");
		assertThat(row(control).asOf()).isEqualTo(LocalDate.parse(NEXT));

		// 일보 스냅샷의 미판정 계약 수
		JsonNode daily = ok(getJson(head, "/api/reports/daily?date=" + D));
		assertThat(daily.get("closed").asBoolean()).isTrue();
		assertThat(daily.get("unjudgedCount").asInt()).isEqualTo(closing.get("judgeFailures").asInt());

		// 실패 기록은 지우거나 해결 표시 밖의 수정을 할 수 없다
		assertThatThrownBy(() -> jdbc.sql("delete from daily_closing_failure where account_id = :a")
				.param("a", broken).update()).hasMessageContaining("삭제할 수 없습니다");
		assertThatThrownBy(() -> jdbc.sql("update daily_closing_failure set message = 'x' where account_id = :a")
				.param("a", broken).update()).hasMessageContaining("해결 표시만");

		// 데이터를 고치기 전 재판정은 실패하고 아무것도 바꾸지 않는다
		var stillBroken = postJson(admin, "/api/operation/accounts/" + broken + "/rejudge", Map.of()).andReturn()
				.getResponse();
		assertThat(stillBroken.getStatus()).isEqualTo(422);
		assertThat(stillBroken.getContentAsString()).contains("REJUDGE_FAILED");
		assertThat(failureOf(admin, broken).get("resolved").asBoolean()).isFalse();
		assertThat(row(broken)).isEqualTo(brokenBefore);

		// 데이터를 고친 뒤 재판정 → 해결, 대조군과 같은 기준·같은 결과
		jdbc.sql("update account_event set event_date = '2027-09-05' where account_id = :a and type = 'PAYMENT'")
				.param("a", broken).update();
		JsonNode rejudged = ok(postJson(admin, "/api/operation/accounts/" + broken + "/rejudge", Map.of()));
		assertThat(rejudged.get("accountId").asLong()).isEqualTo(broken);
		JsonNode resolved = failureOf(admin, broken);
		assertThat(resolved.get("resolved").asBoolean()).isTrue();
		assertThat(resolved.get("resolvedAt").isNull()).isFalse();

		AccountRow fixed = row(broken);
		AccountRow reference = row(control);
		assertThat(fixed.asOf()).isEqualTo(LocalDate.parse(NEXT)).isEqualTo(reference.asOf());
		assertThat(fixed).isEqualTo(reference);

		// 이미 해결한 계약은 다시 재판정하지 않는다
		var again = postJson(admin, "/api/operation/accounts/" + broken + "/rejudge", Map.of()).andReturn()
				.getResponse();
		assertThat(again.getStatus()).isEqualTo(404);
		assertThat(again.getContentAsString()).contains("FAILURE_NOT_FOUND");
	}

	@Test
	void R76_재판정은_시스템관리자만_할_수_있다() throws Exception {
		for (String role : List.of("UNDERWRITER", "UNDERWRITING_LEAD", "COLLECTOR", "COLLECTION_LEAD", "TELLER",
				"HEAD")) {
			MockHttpSession s = login(role);
			assertThat(postJson(s, "/api/operation/accounts/1/rejudge", Map.of()).andReturn().getResponse()
					.getStatus()).as(role).isEqualTo(403);
		}
		// 시스템관리자는 권한 검사를 통과한다 (실패 기록이 없는 계약이라 404)
		MockHttpSession admin = login("SYSTEM_ADMIN");
		assertThat(postJson(admin, "/api/operation/accounts/" + Long.MAX_VALUE + "/rejudge", Map.of()).andReturn()
				.getResponse().getStatus()).isEqualTo(404);
	}
}
