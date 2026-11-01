package dev.arche;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arche.support.IntegrationTest;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;

/**
 * 보고서·회계 분개·엑셀 다운로드 (R65~R68). 다른 통합 테스트와 겹치지 않는 2027년 1·2월 말일에 마감한다.
 */
class ReportingIT extends IntegrationTest {

	private static long amount(JsonNode daily, String section, String item) {
		for (JsonNode l : daily.get("lines")) {
			if (l.get("section").asString().equals(section) && l.get("item").asString().equals(item)) {
				return l.get("amount").asLong();
			}
		}
		throw new AssertionError(section + "/" + item + " 없음");
	}

	@Test
	void 일마감이_일보_분개_월마감을_저장하고_과거_일계는_바뀌지_않는다() throws Exception {
		businessDate("2027-01-31");
		MockHttpSession head = login("HEAD");
		MockHttpSession admin = login("SYSTEM_ADMIN");
		long account = openLoan(head, customer(head, "보고서"), "P1", 10_000_000, "19.9", 12, 0, 10, "2027-01-31",
				null);
		String accountNo = detail(head, account).get("accountNo").asString();

		// 마감 전: 미리보기 (저장하지 않음)
		JsonNode preview = ok(getJson(head, "/api/reports/daily?date=2027-01-31"));
		assertThat(preview.get("closed").asBoolean()).isFalse();
		assertThat(amount(preview, "DISBURSEMENT", "DISBURSE")).isGreaterThanOrEqualTo(10_000_000);

		JsonNode journal = ok(getJson(head, "/api/reports/journal?date=2027-01-31"));
		assertThat(journal.get("balanced").asBoolean()).isTrue();
		Map<String, long[]> mine = new HashMap<>();
		for (JsonNode l : journal.get("lines")) {
			if (l.get("accountNo").asString().equals(accountNo)) {
				long[] t = mine.computeIfAbsent(l.get("accountCode").asString(), k -> new long[2]);
				t[0] += l.get("debit").asLong();
				t[1] += l.get("credit").asLong();
			}
		}
		assertThat(mine.get("A-LOAN")).containsExactly(10_000_000, 0);
		assertThat(mine.get("A-CASH")).containsExactly(0, 10_000_000);

		// 일마감 (말일) → 일보·분개 일계·월마감 스냅샷
		ok(postJson(admin, "/api/operation/closings", Map.of()));
		JsonNode daily = ok(getJson(head, "/api/reports/daily?date=2027-01-31"));
		assertThat(daily.get("closed").asBoolean()).isTrue();
		assertThat(daily.get("balancesAvailable").asBoolean()).isTrue();
		assertThat(daily.get("journalBalanced").asBoolean()).isTrue();
		assertThat(amount(daily, "BALANCE", "PRINCIPAL")).isGreaterThanOrEqualTo(10_000_000);

		boolean listed = false;
		for (JsonNode m : ok(getJson(head, "/api/reports/months"))) {
			listed |= m.get("month").asString().equals("2027-01");
		}
		assertThat(listed).isTrue();
		String bucket = jdbc.sql("select bucket from report_month_end_account where month = '2027-01-01'"
				+ " and account_id = :a").param("a", account).query(String.class).single();
		assertThat(bucket).isEqualTo("CURRENT");

		JsonNode provision = ok(getJson(head, "/api/reports/provision?month=2027-01"));
		assertThat(provision.get("closed").asBoolean()).isTrue();
		assertThat(provision.get("rows").get(0).get("bucket").asString()).isEqualTo("CURRENT");
		assertThat(provision.get("rows").get(0).get("claim").asLong()).isGreaterThanOrEqualTo(10_000_000);

		// 2월 말일 마감 → 1월 말 → 2월 말 전이 (첫 납입일 03-10이라 여전히 정상)
		businessDate("2027-02-28");
		ok(postJson(admin, "/api/operation/closings", Map.of()));
		JsonNode roll = ok(getJson(head, "/api/reports/roll-rate?month=2027-02"));
		assertThat(roll.get("previousAvailable").asBoolean()).isTrue();
		assertThat(roll.get("currentClosed").asBoolean()).isTrue();
		JsonNode currentRow = roll.get("rows").get(0);
		assertThat(currentRow.get("from").asString()).isEqualTo("CURRENT");
		assertThat(currentRow.get("cells").get(0).get("count").asLong()).isPositive();

		// 매핑을 바꿔도 마감된 날의 계정별 일계는 마감 시점 값 (R68)
		try {
			jdbc.sql("update journal_rule set counter_account = 'E-ADJ' where posting_type = 'DISBURSE'").update();
			JsonNode after = ok(getJson(head, "/api/reports/journal?date=2027-01-31"));
			assertThat(after.get("closed").asBoolean()).isTrue();
			assertThat(after.get("matchesSnapshot").asBoolean()).isFalse();
			boolean hasAdj = false;
			for (JsonNode t : after.get("totals")) {
				hasAdj |= t.get("accountCode").asString().equals("E-ADJ") && t.get("credit").asLong() >= 10_000_000;
			}
			assertThat(hasAdj).as("일계는 스냅샷").isFalse();
		} finally {
			jdbc.sql("update journal_rule set counter_account = 'A-CASH' where posting_type = 'DISBURSE'").update();
		}
	}

	@Test
	void 엑셀_다운로드는_권한_사유_접근기록_마스킹을_지킨다() throws Exception {
		businessDate("2026-06-12");
		MockHttpSession head = login("HEAD");
		long account = openLoan(head, customer(head, "엑셀고객"), "P1", 3_000_000, "19.9", 12, 0, 10, "2026-05-10",
				null);
		String accountNo = detail(head, account).get("accountNo").asString();

		// 엑셀 다운로드 권한 없음 (채권담당)
		MockHttpSession collector = login("COLLECTOR");
		assertThat(postJson(collector, "/api/exports/loans", Map.of("reason", "월간 점검"))
				.andReturn().getResponse().getStatus()).isEqualTo(403);
		// 심사팀장: 보고서는 되지만 계약 목록은 계약 조회 권한이 없어 불가 (R68 ②)
		MockHttpSession uwLead = login("UNDERWRITING_LEAD");
		assertThat(postJson(uwLead, "/api/exports/loans", Map.of("reason", "월간 점검"))
				.andReturn().getResponse().getStatus()).isEqualTo(403);
		var report = postJson(uwLead, "/api/exports/reports", Map.of("kind", "PROVISION", "reason", "충당금 검토"))
				.andReturn().getResponse();
		assertThat(report.getStatus()).isEqualTo(200);
		assertThat(report.getContentType()).startsWith("application/vnd.openxmlformats");

		// 사유 필수
		assertThat(postJson(head, "/api/exports/loans", Map.of("q", accountNo)).andReturn().getResponse()
				.getStatus()).isEqualTo(400);
		assertThat(postJson(head, "/api/exports/loans", Map.of("q", accountNo, "reason", "  "))
				.andReturn().getResponse().getStatus()).isIn(400, 422);

		var file = postJson(head, "/api/exports/loans", Map.of("q", accountNo, "reason", "감사 대응 자료"))
				.andReturn().getResponse();
		assertThat(file.getStatus()).isEqualTo(200);
		assertThat(file.getHeader("Content-Disposition")).contains("attachment");
		try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(file.getContentAsByteArray()))) {
			Sheet s = wb.getSheet("계약 목록");
			assertThat(s.getLastRowNum()).isEqualTo(1);
			Row r = s.getRow(1);
			assertThat(r.getCell(0).getStringCellValue()).isEqualTo(accountNo);
			assertThat(r.getCell(1).getStringCellValue()).isEqualTo("엑**객");
			assertThat(r.getCell(2).getStringCellValue()).endsWith("******");
			assertThat(r.getCell(3).getStringCellValue()).contains("****");
			assertThat((long) r.getCell(6).getNumericCellValue()).isEqualTo(3_000_000);
		}

		long logged = jdbc.sql("select count(*) from access_log where action = 'EXCEL_DOWNLOAD'"
				+ " and target_type = 'LOAN_LIST' and reason = '감사 대응 자료' and target_id = 'status=ALL,q'")
				.query(Long.class).single();
		assertThat(logged).isEqualTo(1);
	}

	@Test
	void 보고서_조회는_권한표를_따른다() throws Exception {
		businessDate("2026-06-12");
		assertThat(getJson(login("COLLECTOR"), "/api/reports/daily").andReturn().getResponse().getStatus())
				.isEqualTo(403);
		assertThat(getJson(login("UNDERWRITER"), "/api/reports/provision").andReturn().getResponse().getStatus())
				.isEqualTo(403);
		ok(getJson(login("SYSTEM_ADMIN"), "/api/reports/daily"));
		ok(getJson(login("TELLER"), "/api/reports/provision"));
		ok(getJson(login("COLLECTION_LEAD"), "/api/reports/roll-rate"));
		assertThat(getJson(login("HEAD"), "/api/reports/daily?date=2099-01-01").andReturn().getResponse()
				.getStatus()).isEqualTo(422);
	}
}
