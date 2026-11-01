package dev.arche.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arche.servicing.ServicingReports.AccountBalance;
import dev.arche.servicing.ServicingReports.LedgerLine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 연체 구간(R66), 전이율(R66), 대손충당금(R67), 일보 항목(R65).
 */
class ReportCalculationTest {

	@ParameterizedTest(name = "{0} {1}일 → {2}")
	@CsvSource(delimiter = '|', textBlock = """
			NORMAL               | 0   | CURRENT
			DELINQUENT           | 1   | D1_30
			DELINQUENT           | 30  | D1_30
			DELINQUENT           | 31  | D31_60
			DELINQUENT           | 60  | D31_60
			DELINQUENT           | 61  | D61_90
			DELINQUENT           | 90  | D61_90
			DELINQUENT           | 91  | D91_PLUS
			WORKOUT              | 0   | CURRENT
			WORKOUT              | 12  | D1_30
			ACCELERATED          | 0   | ACCELERATED
			ACCELERATED          | 200 | ACCELERATED
			PAID_OFF             | 0   | CLOSED
			CHARGED_OFF          | 400 | CLOSED
			SOLD                 | 300 | CLOSED
			COLLECTION_STOPPED   | 45  | D31_60
			""")
	void 상태_우선_연체일수_구간(String status, int days, Bucket expected) {
		assertThat(Bucket.of(status, days)).isEqualTo(expected);
	}

	static AccountBalance balance(long id, String status, int days, long principal, long normal, long late,
			long cost, long preAcq) {
		return new AccountBalance(id, "L" + id, "P1", "ORIGINATED", status, days, principal, normal, late, cost, preAcq,
				0, 0, 999, 999);
	}

	@Test
	void 채권_잔액은_확정분만_넣는다() {
		assertThat(Provision.claim(balance(1, "DELINQUENT", 10, 1_000_000, 10_000, 2_000, 3_000, 0)))
				.isEqualTo(1_015_000);
	}

	@Test
	void 대손충당금은_구간_합계에_적립률을_곱해_절사한다() {
		Map<Bucket, BigDecimal> rates = new EnumMap<>(Bucket.class);
		rates.put(Bucket.CURRENT, new BigDecimal("1.00"));
		rates.put(Bucket.D1_30, new BigDecimal("5.00"));
		rates.put(Bucket.D31_60, new BigDecimal("20.00"));
		rates.put(Bucket.D61_90, new BigDecimal("50.00"));
		rates.put(Bucket.D91_PLUS, new BigDecimal("75.00"));
		rates.put(Bucket.ACCELERATED, new BigDecimal("100.00"));
		Provision.Result r = Provision.of(List.of(new Provision.Exposure(Bucket.CURRENT, 1_234_567),
				new Provision.Exposure(Bucket.CURRENT, 10_000_000), new Provision.Exposure(Bucket.D1_30, 999_999),
				new Provision.Exposure(Bucket.ACCELERATED, 5_000_000), new Provision.Exposure(Bucket.CLOSED, 7)),
				rates);
		// 정상 11,234,567 × 1% = 112,345.67 → 112,345 / 1~30 999,999 × 5% = 49,999.95 → 49,999
		assertThat(r.rows()).extracting(Provision.Row::bucket, Provision.Row::count, Provision.Row::claim,
				Provision.Row::provision).containsExactly(
						org.assertj.core.groups.Tuple.tuple(Bucket.CURRENT, 2L, 11_234_567L, 112_345L),
						org.assertj.core.groups.Tuple.tuple(Bucket.D1_30, 1L, 999_999L, 49_999L),
						org.assertj.core.groups.Tuple.tuple(Bucket.D31_60, 0L, 0L, 0L),
						org.assertj.core.groups.Tuple.tuple(Bucket.D61_90, 0L, 0L, 0L),
						org.assertj.core.groups.Tuple.tuple(Bucket.D91_PLUS, 0L, 0L, 0L),
						org.assertj.core.groups.Tuple.tuple(Bucket.ACCELERATED, 1L, 5_000_000L, 5_000_000L));
		assertThat(r.provision()).isEqualTo(112_345 + 49_999 + 5_000_000);
		assertThat(r.count()).isEqualTo(4);
	}

	@Test
	void 전이율은_전월_구간_잔액_기준이다() {
		List<RollRate.Position> sep = List.of(new RollRate.Position(1, Bucket.CURRENT, 1_000_000),
				new RollRate.Position(2, Bucket.CURRENT, 3_000_000), new RollRate.Position(3, Bucket.CURRENT, 4_000_000),
				new RollRate.Position(4, Bucket.D1_30, 2_000_000), new RollRate.Position(5, Bucket.D1_30, 2_000_000));
		List<RollRate.Position> oct = List.of(new RollRate.Position(1, Bucket.CURRENT, 900_000),
				new RollRate.Position(2, Bucket.D1_30, 3_000_000), // 2: 정상 → 1~30 (악화)
				// 3: 완제 (당월 말에 없음 → 종결)
				new RollRate.Position(4, Bucket.CURRENT, 1_900_000), // 4: 1~30 → 정상 (개선)
				new RollRate.Position(5, Bucket.D31_60, 2_000_000), // 5: 1~30 → 31~60 (악화)
				new RollRate.Position(6, Bucket.CURRENT, 5_000_000)); // 6: 신규
		RollRate.Result r = RollRate.of(sep, oct);
		assertThat(r.columns()).last().isEqualTo(Bucket.CLOSED);
		RollRate.Row current = r.rows().getFirst();
		assertThat(current.from()).isEqualTo(Bucket.CURRENT);
		assertThat(current.count()).isEqualTo(3);
		assertThat(current.balance()).isEqualTo(8_000_000);
		assertThat(cell(current, Bucket.CURRENT).balanceRate()).isEqualByComparingTo("12.5");
		assertThat(cell(current, Bucket.D1_30).balanceRate()).isEqualByComparingTo("37.5");
		assertThat(cell(current, Bucket.CLOSED).balanceRate()).isEqualByComparingTo("50.0");
		assertThat(cell(current, Bucket.CLOSED).countRate()).isEqualByComparingTo("33.3");
		assertThat(current.worsenRate()).isEqualByComparingTo("37.5");
		assertThat(current.cureRate()).isEqualByComparingTo("0");

		RollRate.Row d1 = r.rows().get(1);
		assertThat(d1.worsenRate()).isEqualByComparingTo("50.0");
		assertThat(d1.cureRate()).isEqualByComparingTo("50.0");

		RollRate.Row fresh = r.rows().getLast();
		assertThat(fresh.from()).isNull();
		assertThat(cell(fresh, Bucket.CURRENT).count()).isEqualTo(1);
		assertThat(cell(fresh, Bucket.CURRENT).balance()).isEqualTo(5_000_000);
	}

	private static RollRate.Cell cell(RollRate.Row row, Bucket to) {
		return row.cells().stream().filter(c -> c.to() == to).findFirst().orElseThrow();
	}

	static long seq = 100;

	static LedgerLine line(String type, String reversedType, LocalDate valueDate, Long correctionId, long amount,
			long principal, long normal, long suspense) {
		return new LedgerLine(seq++, 1, "L1", type, reversedType, valueDate, D, null, correctionId, amount, principal,
				normal, 0, 0, 0, 0, suspense, 0, normal, 0, null);
	}

	static final LocalDate D = LocalDate.of(2026, 7, 10);

	private static long amount(List<DailyReportBuilder.Line> lines, String section, String item) {
		return lines.stream().filter(l -> l.section().equals(section) && l.item().equals(item)).findFirst()
				.orElseThrow(() -> new AssertionError(section + "/" + item)).amount();
	}

	private static long count(List<DailyReportBuilder.Line> lines, String section, String item) {
		return lines.stream().filter(l -> l.section().equals(section) && l.item().equals(item)).findFirst()
				.orElseThrow().count();
	}

	@Test
	void 당일_취소는_상계되어_일보에_나타나지_않는다_R27() {
		List<DailyReportBuilder.Line> lines = DailyReportBuilder.ledgerLines(D, List.of(
				line("REPAYMENT", null, D, null, 100_000, 90_000, 10_000, 0),
				line("REVERSAL", "REPAYMENT", D, null, -100_000, -90_000, -10_000, 0),
				line("DISBURSE", null, D, null, 5_000_000, 0, 0, 0)));
		assertThat(amount(lines, "POSTING", "REPAYMENT")).isZero();
		assertThat(count(lines, "POSTING", "REPAYMENT")).isZero();
		assertThat(amount(lines, "ALLOCATION", "PRINCIPAL")).isZero();
		assertThat(amount(lines, "DISBURSEMENT", "DISBURSE")).isEqualTo(5_000_000);
		assertThat(amount(lines, "DISBURSEMENT", "ACQUIRE")).isZero();
		assertThat(amount(lines, "CASH", "CORRECTION")).isZero();
	}

	@Test
	void 마감_후_정정은_처리일_정정_항목에_차액으로_나온다_R28() {
		LocalDate past = D.minusDays(5);
		// 처리일 D에 정정번호 7로 과거 기산일 입금 역분개 + 다른 계약 재기록, 그리고 재충당 내부 거래(가수금 적용)는 정정에서 제외
		List<DailyReportBuilder.Line> lines = DailyReportBuilder.ledgerLines(D, List.of(
				line("REVERSAL", "REPAYMENT", past, 7L, -1_135_616, -1_000_000, -135_616, 0),
				line("REPAYMENT", null, past, 7L, 1_135_616, 1_126_000, 9_616, 0),
				line("SUSPENSE_APPLY", null, past, 7L, 50_000, 50_000, 0, -50_000)));
		assertThat(amount(lines, "CASH", "CORRECTION")).isZero();
		assertThat(count(lines, "CASH", "CORRECTION")).isZero();
		assertThat(amount(lines, "ALLOCATION", "PRINCIPAL")).isEqualTo(-1_000_000 + 1_126_000 + 50_000);
		assertThat(amount(lines, "ALLOCATION", "NORMAL_INTEREST")).isEqualTo(-135_616 + 9_616);

		List<DailyReportBuilder.Line> cancelOnly = DailyReportBuilder.ledgerLines(D,
				List.of(line("REVERSAL", "SUSPENSE_IN", past, 8L, -300_000, 0, 0, -300_000)));
		assertThat(amount(cancelOnly, "CASH", "CORRECTION")).isEqualTo(-300_000);
		assertThat(count(cancelOnly, "CASH", "CORRECTION")).isEqualTo(-1);
	}

	@Test
	void 잔액_상태_구간_행() {
		List<DailyReportBuilder.Line> lines = DailyReportBuilder.balanceLines(List.of(
				balance(1, "NORMAL", 0, 1_000_000, 0, 0, 0, 0),
				balance(2, "DELINQUENT", 40, 2_000_000, 30_000, 5_000, 0, 0),
				balance(3, "PAID_OFF", 0, 0, 0, 0, 0, 0),
				balance(4, "NEW_FUTURE_STATUS", 0, 500_000, 0, 0, 0, 0)),
				new DailyReportBuilder.PaymentTotals(3, 450_000, 70_000));
		assertThat(amount(lines, "BALANCE", "PRINCIPAL")).isEqualTo(3_500_000);
		assertThat(count(lines, "BALANCE", "PRINCIPAL")).isEqualTo(3);
		assertThat(amount(lines, "BALANCE", "CLAIM")).isEqualTo(3_535_000);
		assertThat(count(lines, "STATUS", "NEW_FUTURE_STATUS")).isEqualTo(1);
		assertThat(count(lines, "BUCKET", "D31_60")).isEqualTo(1);
		assertThat(amount(lines, "BUCKET", "D31_60")).isEqualTo(2_035_000);
		assertThat(count(lines, "BUCKET", "CLOSED")).isEqualTo(1);
		assertThat(amount(lines, "CASH", "PAYMENT_ALLOCATED")).isEqualTo(450_000);
		assertThat(amount(lines, "CASH", "UNMATCHED_OPEN")).isEqualTo(70_000);
	}
}
