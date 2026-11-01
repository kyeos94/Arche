package dev.arche;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.arche.support.IntegrationTest;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

/**
 * MVP "한 줄기" 3종: 가상계좌 웹훅, 엑셀 입금 일괄 등록, 신청 → 실행 → 계약 이관(Outbox). + 원장 추가 전용 제약.
 */
class IntegrationFlowsIT extends IntegrationTest {

	@Value("${arche.integration.virtual-account.secret}")
	String secret;

	// ------------------------------------------------------------------ 가상계좌 웹훅

	@Test
	void 가상계좌_입금_통지_자동_충당과_중복_통지_무시() throws Exception {
		businessDate("2026-06-12");
		MockHttpSession head = login("HEAD");
		long account = openLoan(head, customer(head, "웹훅"), "P5", 5_000_000, "19.5", 12, 0, 20, "2026-05-01",
				null);
		String va = detail(head, account).get("virtualAccountNo").asString();
		String txId = tx();
		String payload = json.writeValueAsString(Map.of("bankTxId", txId, "virtualAccountNo", va, "amount", 500_000,
				"bankDate", "2026-06-12", "depositorName", "웹훅"));

		JsonNode first = webhook(payload, sign(payload), 200);
		assertThat(first.get("result").asString()).isEqualTo("ALLOCATED");
		JsonNode retry = webhook(payload, sign(payload), 200);
		assertThat(retry.get("result").asString()).isEqualTo("DUPLICATE");
		assertThat(retry.get("paymentId").asLong()).isEqualTo(first.get("paymentId").asLong());
		long count = jdbc.sql("select count(*) from payment where bank_tx_id = :t").param("t", txId)
				.query(Long.class).single();
		assertThat(count).isEqualTo(1);
		assertThat(detail(head, account).get("totalPaid").asLong()).isEqualTo(500_000);

		webhook(payload, "bad-signature", 401);

		String unknown = json.writeValueAsString(Map.of("bankTxId", tx(), "virtualAccountNo", "000000000000",
				"amount", 10_000, "bankDate", "2026-06-12", "depositorName", "모름"));
		assertThat(webhook(unknown, sign(unknown), 200).get("result").asString()).isEqualTo("UNMATCHED");
	}

	private JsonNode webhook(String payload, String signature, int expected) throws Exception {
		var r = mvc.perform(post("/api/integration/virtual-account/deposits").contentType(MediaType.APPLICATION_JSON)
				.header("X-Signature", signature).content(payload)).andReturn().getResponse();
		assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(expected);
		return json.readTree(r.getContentAsString());
	}

	private String sign(String payload) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
	}

	// ------------------------------------------------------------------ 엑셀 일괄 등록

	@Test
	void 입금_일괄_등록_1000행() throws Exception {
		businessDate("2026-06-12");
		MockHttpSession head = login("HEAD");
		MockHttpSession teller = login("TELLER");
		List<String> accountNos = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			long id = openLoan(head, customer(head, "일괄" + i), "P5", 20_000_000, "19.5", 12, 0, 20, "2026-06-01",
					null);
			accountNos.add(detail(head, id).get("accountNo").asString());
		}
		String prefix = tx();
		byte[] file;
		try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sheet = wb.createSheet();
			Row h = sheet.createRow(0);
			String[] headers = { "계약번호", "입금일", "입금액", "입금자명", "거래고유번호" };
			for (int c = 0; c < headers.length; c++) {
				h.createCell(c).setCellValue(headers[c]);
			}
			for (int r = 1; r <= 1_000; r++) {
				Row row = sheet.createRow(r);
				row.createCell(0).setCellValue(accountNos.get(r % 10));
				row.createCell(1).setCellValue("2026-06-12");
				row.createCell(2).setCellValue(10_000);
				row.createCell(3).setCellValue("일괄");
				row.createCell(4).setCellValue(prefix + "-" + r);
			}
			Row bad1 = sheet.createRow(1_001);
			bad1.createCell(0).setCellValue("L99999999");
			bad1.createCell(1).setCellValue("2026-06-12");
			bad1.createCell(2).setCellValue(10_000);
			bad1.createCell(4).setCellValue(prefix + "-bad1");
			Row bad2 = sheet.createRow(1_002);
			bad2.createCell(0).setCellValue(accountNos.getFirst());
			bad2.createCell(1).setCellValue("2026-06-12");
			bad2.createCell(2).setCellValue("만원");
			bad2.createCell(4).setCellValue(prefix + "-bad2");
			wb.write(out);
			file = out.toByteArray();
		}

		var upload = mvc.perform(multipart("/api/payments/bulk")
				.file(new MockMultipartFile("file", "payments.xlsx",
						"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", file))
				.session(teller).with(csrf()));
		JsonNode job = ok(upload);
		assertThat(job.get("totalRows").asInt()).isEqualTo(1_002);
		assertThat(job.get("validRows").asInt()).isEqualTo(1_000);
		long jobId = job.get("id").asLong();

		ok(postJson(teller, "/api/payments/bulk/" + jobId + "/execute", Map.of()));
		JsonNode done = null;
		for (int i = 0; i < 600; i++) {
			done = ok(getJson(teller, "/api/payments/bulk/" + jobId));
			if ("COMPLETED".equals(done.get("status").asString())) {
				break;
			}
			Thread.sleep(500);
		}
		assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
		assertThat(done.get("successRows").asInt()).isEqualTo(1_000);
		assertThat(done.get("failedRows").asInt()).isEqualTo(2);

		var failures = getJson(teller, "/api/payments/bulk/" + jobId + "/failures").andReturn().getResponse();
		assertThat(failures.getStatus()).isEqualTo(200);
		try (XSSFWorkbook wb = new XSSFWorkbook(new java.io.ByteArrayInputStream(failures.getContentAsByteArray()))) {
			assertThat(wb.getSheetAt(0).getLastRowNum()).isEqualTo(2);
		}
		assertThat(detail(head, accountNoToId(accountNos.get(3))).get("totalPaid").asLong()).isEqualTo(1_000_000);
	}

	private long accountNoToId(String accountNo) {
		return jdbc.sql("select id from loan_account where account_no = :n").param("n", accountNo).query(Long.class)
				.single();
	}

	// ------------------------------------------------------------------ 신청 → 실행 → 계약 이관

	@Test
	void 신청부터_계약_생성까지_한_줄기() throws Exception {
		businessDate("2026-06-12");
		MockHttpSession underwriter = login("UNDERWRITER");
		MockHttpSession uwLead = login("UNDERWRITING_LEAD");
		long customerId = customer(uwLead, "신청");
		long appId = ok(postJson(underwriter, "/api/applications", Map.of("customerId", customerId, "productCode",
				"P1", "requestedAmount", 8_000_000, "termMonths", 24, "graceMonths", 0, "dueDay", 15))).get("id")
				.asLong();
		for (String to : List.of("PRE_SCREENING", "ASSIGNED", "UNDERWRITING")) {
			ok(postJson(underwriter, "/api/applications/" + appId + "/transitions", Map.of("to", to)));
		}
		// 필수 서류는 확인을 마쳐야 승인할 수 있다 (R54): 서면 원본 접수 → 확인
		for (JsonNode d : ok(getJson(underwriter, "/api/applications/" + appId + "/documents"))) {
			if (d.get("required").asBoolean()) {
				String doc = "/api/applications/" + appId + "/documents/" + d.get("id").asLong();
				ok(postJson(underwriter, doc + "/paper", Map.of("note", "원본 대조")));
				ok(postJson(underwriter, doc + "/verify", Map.of()));
			}
		}
		// 심사역 전결은 1,000만원 이하
		assertThat(postJson(underwriter, "/api/applications/" + appId + "/approval",
				Map.of("amount", 12_000_000, "rate", "18.9")).andReturn().getResponse().getStatus()).isEqualTo(403);
		ok(postJson(underwriter, "/api/applications/" + appId + "/approval",
				Map.of("amount", 8_000_000, "rate", "18.9")));
		ok(postJson(underwriter, "/api/applications/" + appId + "/transitions", Map.of("to", "CONTRACTED")));
		ok(postJson(underwriter, "/api/applications/" + appId + "/disbursement", Map.of()));

		Long accountId = null;
		for (int i = 0; i < 100 && accountId == null; i++) {
			accountId = jdbc.sql("select id from loan_account where application_id = :a").param("a", appId)
					.query(Long.class).optional().orElse(null);
			if (accountId == null) {
				Thread.sleep(100);
			}
		}
		assertThat(accountId).as("실행 이벤트로 계약이 생성돼야 한다").isNotNull();
		long completed = jdbc.sql("select count(*) from event_publication where completion_date is not null")
				.query(Long.class).single();
		assertThat(completed).isPositive();
		JsonNode history = ok(getJson(underwriter, "/api/applications/" + appId + "/history"));
		assertThat(history).hasSize(7);
	}

	@Test
	void 만_19세_미만은_사전심사에서_자동_거절() throws Exception {
		businessDate("2026-06-12");
		MockHttpSession uw = login("UNDERWRITER");
		long minor = customer(uw, "미성년", "2010-01-01");
		long appId = ok(postJson(uw, "/api/applications", Map.of("customerId", minor, "productCode", "P2",
				"requestedAmount", 1_000_000, "termMonths", 6, "graceMonths", 0, "dueDay", 5))).get("id").asLong();
		JsonNode r = ok(postJson(uw, "/api/applications/" + appId + "/transitions", Map.of("to", "PRE_SCREENING")));
		assertThat(r.get("status").asString()).isEqualTo("REJECTED");
	}

	// ------------------------------------------------------------------ 원장 추가 전용

	@Test
	void 원장은_UPDATE_DELETE_할_수_없다() throws Exception {
		businessDate("2026-06-12");
		MockHttpSession head = login("HEAD");
		long account = openLoan(head, customer(head, "원장"), "P1", 10_000_000, "19.9", 12, 0, 10, "2026-06-01",
				null);
		assertThatThrownBy(() -> jdbc.sql("update ledger_entry set amount = 0 where account_id = :a")
				.param("a", account).update()).hasMessageContaining("수정·삭제할 수 없습니다");
		assertThatThrownBy(() -> jdbc.sql("delete from ledger_entry where account_id = :a").param("a", account)
				.update()).hasMessageContaining("수정·삭제할 수 없습니다");
	}
}
