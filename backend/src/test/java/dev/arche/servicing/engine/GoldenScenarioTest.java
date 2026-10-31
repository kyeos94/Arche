package dev.arche.servicing.engine;

import static dev.arche.servicing.engine.Golden.G1;
import static dev.arche.servicing.engine.Golden.G15;
import static dev.arche.servicing.engine.Golden.G2;
import static dev.arche.servicing.engine.Golden.concat;
import static dev.arche.servicing.engine.Golden.pay;
import static dev.arche.servicing.engine.Golden.payScheduled;
import static dev.arche.servicing.engine.Golden.posting;
import static dev.arche.servicing.engine.Golden.simulate;
import static java.time.LocalDate.parse;
import static org.assertj.core.api.Assertions.assertThat;

import dev.arche.common.DateRange;
import dev.arche.common.InterestRate;
import dev.arche.common.Money;
import dev.arche.common.RepaymentMethod;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

/**
 * docs/golden-cases.md 시나리오 사례. 기대값은 문서 그대로이며, 다르면 코드를 고친다 (CLAUDE.md).
 */
class GoldenScenarioTest {

	private static Money won(long v) {
		return Money.won(v);
	}

	@ParameterizedTest(name = "G5 {4}")
	@CsvFileSource(resources = "/golden/G05-daily-interest.csv", numLinesToSkip = 1)
	void G5_일할_이자(long balance, String rate, long days, long interest, String point) {
		assertThat(InterestRate.of(rate).interest(won(balance), days)).isEqualTo(won(interest));
	}

	@Test
	void G5_당일_실행_당일_상환은_1일분_이자() {
		LoanTerms terms = LoanTerms.originated(RepaymentMethod.EQUAL_INSTALLMENT, 1_000_000, parse("2026-01-12"), 12,
				0, 10, "19.9", "0");
		assertThat(simulate(terms, List.of(), "2026-01-12").accruedNormalInterest()).isEqualTo(won(545));
	}

	@Test
	void G6_금리_변경일에서_구간_분할() {
		var result = InterestAccrual.accrue(DateRange.of(parse("2026-06-10"), parse("2026-07-10")),
				d -> new InterestAccrual.Basis(won(8_000_000),
						d.isBefore(parse("2026-06-25")) ? InterestRate.of("19.9") : InterestRate.of("17.9")));
		assertThat(result.segments()).extracting(InterestAccrual.Segment::interest)
				.containsExactly(won(65_424), won(58_849));
		assertThat(result.total()).isEqualTo(won(124_273));
	}

	@Test
	void G7_유예를_넘긴_연체_후_납부() {
		var events = concat(payScheduled(G2, 1), List.of(pay("P", "2026-06-12", 1_218_465)));
		var r = simulate(G2, events, "2026-06-12");
		Posting p = posting(r, "P");

		assertThat(p.lateInterest()).isEqualTo(won(1_479));
		assertThat(p.normalInterest()).isEqualTo(won(216_986));
		assertThat(p.principal()).isEqualTo(won(1_000_000));
		assertThat(p.suspense()).isEqualTo(Money.ZERO);
		assertThat(r.principalBalance()).isEqualTo(won(10_000_000));
		assertThat(r.status()).isEqualTo(AccountStatus.NORMAL);

		var at0625 = simulate(G2, events, "2026-06-25");
		assertThat(at0625.accruedNormalInterest()).isEqualTo(won(53_424));
		assertThat(at0625.installment(3).orElseThrow().principalDue()).isEqualTo(won(1_000_000));
	}

	@Nested
	@DisplayName("G8·G21 기한이익상실 (G2 대출)")
	class G8 {

		final List<LoanEvent> events = payScheduled(G2, 2);

		@Test
		void G8_통지_도달_상실일과_미납_이자() {
			var r = simulate(G2, events, "2026-08-25");

			assertThat(r.notices()).singleElement().satisfies(n -> {
				assertThat(n.reasonDate()).isEqualTo(parse("2026-07-28"));
				assertThat(n.sentOn()).isEqualTo(parse("2026-07-28"));
				assertThat(n.deemedArrivalOn()).isEqualTo(parse("2026-07-30"));
				assertThat(n.accelerationDate()).isEqualTo(parse("2026-08-13"));
				assertThat(n.status()).isEqualTo(NoticeStatus.EXECUTED);
			});
			assertThat(r.status()).isEqualTo(AccountStatus.ACCELERATED);
			assertThat(r.outstandingNormalInterest()).isEqualTo(won(378_082));
			assertThat(r.outstandingLateInterest()).isEqualTo(won(2_465 + 3_123 + 9_863));
		}

		@Test
		void G21_상실_후_입금_충당과_다음_달_이자() {
			var withPayment = concat(events, List.of(pay("P", "2026-08-25", 1_000_000)));
			Posting p = posting(simulate(G2, withPayment, "2026-08-25"), "P");

			assertThat(p.lateInterest()).isEqualTo(won(15_451));
			assertThat(p.normalInterest()).isEqualTo(won(378_082));
			assertThat(p.principal()).isEqualTo(won(606_467));
			assertThat(p.principalBalanceAfter()).isEqualTo(won(9_393_533));

			var next = simulate(G2, withPayment, "2026-09-25");
			assertThat(next.status()).isEqualTo(AccountStatus.ACCELERATED);
			assertThat(next.accruedNormalInterest()).isEqualTo(won(119_671));
			assertThat(next.accruedLateInterest()).isEqualTo(won(23_934));
		}
	}

	@ParameterizedTest(name = "G9 {0}")
	@CsvFileSource(resources = "/golden/G09-prepayment-fee.csv", numLinesToSkip = 1)
	void G9_중도상환수수료(String caseName, long principal, String feeRate, LocalDate disbursedOn, LocalDate lastDue,
			LocalDate repaidOn, long fee) {
		assertThat(ScheduleMath.prepaymentFee(won(principal), InterestRate.of(feeRate), disbursedOn, lastDue,
				repaidOn)).isEqualTo(won(fee));
	}

	@Test
	void G10_충당_순서() {
		List<Money> dues = List.of(won(120_000), won(8_000), won(160_000), won(500_000), won(300_000));

		var small = new Waterfall(won(500_000));
		assertThat(dues.stream().map(small::take).toList())
				.containsExactly(won(120_000), won(8_000), won(160_000), won(212_000), Money.ZERO);
		assertThat(small.left()).isEqualTo(Money.ZERO);

		var large = new Waterfall(won(1_500_000));
		assertThat(dues.stream().map(large::take).toList())
				.containsExactly(won(120_000), won(8_000), won(160_000), won(500_000), won(300_000));
		assertThat(large.left()).isEqualTo(won(412_000));
	}

	@Test
	void G11_미확인_입금_소급_매칭() {
		var before = simulate(G2, payScheduled(G2, 1), "2026-06-12");
		assertThat(before.status()).isEqualTo(AccountStatus.DELINQUENT);
		assertThat(before.delinquencyDays()).isEqualTo(18);
		assertThat(before.principalBalance()).isEqualTo(won(11_000_000));
		assertThat(before.outstandingNormalInterest()).isEqualTo(won(216_986));
		assertThat(before.outstandingLateInterest()).isEqualTo(won(1_479));

		var after = simulate(G2, concat(payScheduled(G2, 1), List.of(pay("U", "2026-05-26", 1_135_616))),
				"2026-06-12");
		assertThat(posting(after, "U").valueDate()).isEqualTo(parse("2026-05-25"));
		assertThat(after.status()).isEqualTo(AccountStatus.NORMAL);
		assertThat(after.delinquencyDays()).isZero();
		assertThat(after.principalBalance()).isEqualTo(won(10_000_000));
		assertThat(after.outstandingNormalInterest()).isEqualTo(won(73_972));
		assertThat(after.outstandingLateInterest()).isEqualTo(Money.ZERO);
	}

	@ParameterizedTest(name = "G12 약정 {0}%")
	@CsvFileSource(resources = "/golden/G12-late-rate-cap.csv", numLinesToSkip = 1)
	void G12_연체이율_상한(String contract, String late, String spread) {
		InterestRate rate = InterestRate.of(contract);
		assertThat(rate.lateRate()).isEqualTo(InterestRate.of(late));
		assertThat(rate.lateSpread()).isEqualTo(InterestRate.of(spread));
	}

	static final List<LoanEvent> G15_PAYMENTS = List.of(
			pay("0320", "2026-03-20", 149_589),
			pay("0420", "2026-04-20", 2_000_000),
			pay("0511", "2026-05-11", 1_000_000),
			pay("0520", "2026-05-20", 34_894),
			pay("0622", "2026-06-22", 120_191));

	@Test
	void G15_자유상환_입금_흐름() {
		var r = simulate(G15, G15_PAYMENTS, "2026-06-22");
		record Row(String ref, String valueDate, long interest, long principal, long balance) {
		}
		for (Row row : List.of(
				new Row("0320", "2026-03-20", 149_589, 0, 10_000_000),
				new Row("0420", "2026-04-20", 165_616, 1_834_384, 8_165_616),
				new Row("0511", "2026-05-11", 91_611, 908_389, 7_257_227),
				new Row("0520", "2026-05-20", 34_894, 0, 7_257_227),
				new Row("0622", "2026-06-20", 120_191, 0, 7_257_227))) {
			Posting p = posting(r, row.ref());
			assertThat(p.valueDate()).as(row.ref()).isEqualTo(parse(row.valueDate()));
			assertThat(p.normalAccrued()).as(row.ref()).isEqualTo(won(row.interest()));
			assertThat(p.normalInterest()).as(row.ref()).isEqualTo(won(row.interest()));
			assertThat(p.principal()).as(row.ref()).isEqualTo(won(row.principal()));
			assertThat(p.principalBalanceAfter()).as(row.ref()).isEqualTo(won(row.balance()));
		}
		assertThat(r.status()).isEqualTo(AccountStatus.NORMAL);
	}

	@Test
	void G16_자유상환_이자_미납_기한이익상실() {
		var r = simulate(G15, G15_PAYMENTS, "2026-09-21");

		assertThat(r.notices()).singleElement().satisfies(n -> {
			assertThat(n.reasonDate()).isEqualTo(parse("2026-08-21"));
			assertThat(n.deemedArrivalOn()).isEqualTo(parse("2026-08-25"));
			assertThat(n.accelerationDate()).isEqualTo(parse("2026-09-08"));
		});
		assertThat(r.delinquentSince()).isEqualTo(parse("2026-07-20"));
		assertThat(r.delinquencyDays()).isEqualTo(63);
		assertThat(r.outstandingNormalInterest()).isEqualTo(won(360_574));
		assertThat(r.outstandingLateInterest()).isEqualTo(won(1_292));
		assertThat(simulate(G15, G15_PAYMENTS, "2026-07-21").status()).isEqualTo(AccountStatus.DELINQUENT);
	}

	static final List<LoanEvent> G17_PAYMENTS = List.of(
			pay("0331", "2026-03-31", 300_000),
			pay("0515", "2026-05-15", 1_000_000),
			pay("0701", "2026-07-01", 2_000_000));

	@Test
	void G17_매입채권_이자_부과() {
		LoanTerms terms = Golden.g17(true);
		record Row(String ref, String date, long accrued, long cost, long postInterest, long preAcq, long principal,
				long preAcqLeft, long principalLeft, String recovery) {
		}
		for (Row row : List.of(
				new Row("0331", "2026-03-31", 60_383, 150_000, 60_383, 89_617, 0, 1_110_383, 4_000_000, "37.5"),
				new Row("0515", "2026-05-15", 93_698, 0, 93_698, 906_302, 0, 204_081, 4_000_000, "162.5"),
				new Row("0701", "2026-07-01", 97_863, 0, 97_863, 204_081, 1_698_056, 0, 2_301_944, "412.5"))) {
			var r = simulate(terms, G17_PAYMENTS, row.date());
			Posting p = posting(r, row.ref());
			assertThat(p.normalAccrued()).as(row.ref()).isEqualTo(won(row.accrued()));
			assertThat(p.cost()).as(row.ref()).isEqualTo(won(row.cost()));
			assertThat(p.normalInterest()).as(row.ref()).isEqualTo(won(row.postInterest()));
			assertThat(p.preAcqInterest()).as(row.ref()).isEqualTo(won(row.preAcq()));
			assertThat(p.principal()).as(row.ref()).isEqualTo(won(row.principal()));
			assertThat(r.preAcqInterest()).as(row.ref()).isEqualTo(won(row.preAcqLeft()));
			assertThat(r.principalBalance()).as(row.ref()).isEqualTo(won(row.principalLeft()));
			assertThat(r.totalPaid().percentOf(won(800_000))).as(row.ref()).isEqualTo(new BigDecimal(row.recovery()));
			assertThat(r.status()).isEqualTo(AccountStatus.ACCELERATED);
		}
	}

	@Test
	void G18_매입채권_이자_중단() {
		LoanTerms terms = Golden.g17(false);
		record Row(String ref, String date, long cost, long preAcq, long principal, long preAcqLeft,
				long principalLeft) {
		}
		for (Row row : List.of(
				new Row("0331", "2026-03-31", 150_000, 150_000, 0, 1_050_000, 4_000_000),
				new Row("0515", "2026-05-15", 0, 1_000_000, 0, 50_000, 4_000_000),
				new Row("0701", "2026-07-01", 0, 50_000, 1_950_000, 0, 2_050_000))) {
			var r = simulate(terms, G17_PAYMENTS, row.date());
			Posting p = posting(r, row.ref());
			assertThat(p.normalInterest()).isEqualTo(Money.ZERO);
			assertThat(p.cost()).as(row.ref()).isEqualTo(won(row.cost()));
			assertThat(p.preAcqInterest()).as(row.ref()).isEqualTo(won(row.preAcq()));
			assertThat(p.principal()).as(row.ref()).isEqualTo(won(row.principal()));
			assertThat(r.preAcqInterest()).as(row.ref()).isEqualTo(won(row.preAcqLeft()));
			assertThat(r.principalBalance()).as(row.ref()).isEqualTo(won(row.principalLeft()));
		}
	}

	@Test
	void G20_일부_중도상환_필요_총액() {
		var r = simulate(G1, concat(payScheduled(G1, 6), List.of(pay("PRE", "2026-08-01", 2_071_713),
				new LoanEvent.PrepaymentRequest("REQ", parse("2026-08-01"), won(2_000_000)))), "2026-08-01");

		assertThat(posting(r, "REQ-ARREARS").normalInterest()).isEqualTo(won(62_837));
		Posting pre = posting(r, "REQ");
		assertThat(pre.fee()).isEqualTo(won(8_876));
		assertThat(pre.principal()).isEqualTo(won(2_000_000));
		assertThat(r.principalBalance()).isEqualTo(won(3_238_826));
		assertThat(r.installmentAmount()).isEqualTo(won(571_564));
		assertThat(r.suspense()).isEqualTo(Money.ZERO);
		assertThat(r.scheduleVersion()).isEqualTo(2);
		assertThat(r.warnings()).isEmpty();
	}

	@Test
	void G22_완제_금액() {
		var events = payScheduled(G2, 5);
		var quote = simulate(G2, events, "2026-09-10");
		assertThat(quote.accruedNormalInterest()).isEqualTo(won(46_027));
		assertThat(quote.prepaymentFeeIfRepaidToday()).isEqualTo(won(44_497));
		assertThat(quote.payoffAmount()).isEqualTo(won(7_090_524));

		var paid = simulate(G2, concat(events, List.of(pay("PAYOFF", "2026-09-10", 7_090_524),
				new LoanEvent.PrepaymentRequest("REQ", parse("2026-09-10"), null))), "2026-09-10");
		assertThat(paid.status()).isEqualTo(AccountStatus.PAID_OFF);
		assertThat(paid.refundPending()).isEqualTo(Money.ZERO);
	}

	@Test
	void G24_금리_변경_회차_이자_구간_합산() {
		var r = simulate(G1.withRatePeriods(List.of(new RatePeriod(parse("2026-01-10"), InterestRate.of("19.9")),
				new RatePeriod(parse("2026-06-25"), InterestRate.of("17.9")))),
				concat(payScheduled(G1, 5), List.of(pay("INST-6", "2026-07-10", 920_880))), "2026-07-10");
		Posting p = posting(r, "DUE-2026-07-10");
		assertThat(p.normalInterest()).isEqualTo(won(49_604 + 44_618));
		assertThat(p.principal()).isEqualTo(won(826_658));
		assertThat(r.principalBalance()).isEqualTo(won(5_238_826));
		assertThat(r.installmentAmount()).isEqualTo(won(919_285));
	}

	@Test
	void G25_약정일_변경_만기_이동() {
		var r = simulate(G1, concat(payScheduled(G1, 7),
				List.of(new LoanEvent.DueDayChange("DUE", parse("2026-07-15"), 25))), "2026-08-10");
		assertThat(r.installment(7).orElseThrow().dueDate()).isEqualTo(parse("2026-08-10"));
		assertThat(r.maturityDate()).isEqualTo(parse("2027-01-25"));
		assertThat(r.installmentAmount()).isEqualTo(won(925_866));
	}

	@Test
	void G26_금액_오입력_당일_정정() {
		var wrong = simulate(G15, List.of(G15_PAYMENTS.get(0), G15_PAYMENTS.get(1), G15_PAYMENTS.get(2)),
				"2026-05-20");
		assertThat(wrong.accruedNormalInterest()).isEqualTo(won(34_894));

		var fixed = List.<LoanEvent>of(G15_PAYMENTS.get(0), G15_PAYMENTS.get(1), pay("0511-FIX", "2026-05-11", 100_000));
		var at0511 = simulate(G15, fixed, "2026-05-11");
		Posting p = posting(at0511, "0511-FIX");
		assertThat(p.normalInterest()).isEqualTo(won(91_611));
		assertThat(p.principal()).isEqualTo(won(8_389));
		assertThat(at0511.principalBalance()).isEqualTo(won(8_157_227));
		assertThat(simulate(G15, fixed, "2026-05-20").accruedNormalInterest()).isEqualTo(won(39_221));
	}

	@Test
	void G27_과거_입금_취소로_연체_부활() {
		var paid = concat(payScheduled(G2, 1), List.of(pay("WRONG", "2026-05-26", 1_135_616)));
		var before = simulate(G2, paid, "2026-06-15");
		assertThat(before.status()).isEqualTo(AccountStatus.NORMAL);
		assertThat(before.principalBalance()).isEqualTo(won(10_000_000));
		assertThat(before.lastSettledOn()).isEqualTo(parse("2026-05-25"));
		assertThat(before.outstandingNormalInterest()).isEqualTo(won(86_301));

		// 취소 = 그 입금이 없었던 것처럼 재생 (R26)
		var after = simulate(G2, payScheduled(G2, 1), "2026-06-15");
		assertThat(after.status()).isEqualTo(AccountStatus.DELINQUENT);
		assertThat(after.delinquencyDays()).isEqualTo(21);
		assertThat(after.principalBalance()).isEqualTo(won(11_000_000));
		assertThat(after.lastSettledOn()).isEqualTo(parse("2026-04-25"));
		assertThat(after.outstandingNormalInterest()).isEqualTo(won(230_547));
		assertThat(after.outstandingLateInterest()).isEqualTo(won(1_726));
		assertThat(after.notices()).isEmpty();
	}

	@Test
	void G28_다른_계약으로_정정_이동() {
		LoanTerms b = LoanTerms.originated(RepaymentMethod.REVOLVING, 3_000_000, parse("2026-04-20"), 12, 0, 20,
				"19.5", "0");
		var r = simulate(b, List.of(pay("B-0520", "2026-05-20", 48_082), pay("MOVED", "2026-05-26", 1_135_616)),
				"2026-05-26");
		Posting p = posting(r, "MOVED");
		assertThat(p.valueDate()).isEqualTo(parse("2026-05-26"));
		assertThat(p.normalInterest()).isEqualTo(won(9_616));
		assertThat(p.principal()).isEqualTo(won(1_126_000));
		assertThat(r.principalBalance()).isEqualTo(won(1_874_000));
		assertThat(r.lastSettledOn()).isEqualTo(parse("2026-05-26"));
	}
}
