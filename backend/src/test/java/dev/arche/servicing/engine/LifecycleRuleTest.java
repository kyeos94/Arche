package dev.arche.servicing.engine;

import static dev.arche.servicing.engine.Golden.G1;
import static dev.arche.servicing.engine.Golden.G2;
import static dev.arche.servicing.engine.Golden.concat;
import static dev.arche.servicing.engine.Golden.pay;
import static dev.arche.servicing.engine.Golden.payScheduled;
import static dev.arche.servicing.engine.Golden.posting;
import static dev.arche.servicing.engine.Golden.simulate;
import static java.time.LocalDate.parse;
import static org.assertj.core.api.Assertions.assertThat;

import dev.arche.common.InterestRate;
import dev.arche.common.Money;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 채권 생애주기 규칙 (정책서 9장 R39~R46): 상각·매각·환매·매각 후 입금 이전, 수기 조정, 상계, 상태 되돌리기, 이자 부과 중단.
 * G2(3회차부터 미납 → 2026-08-13 기한이익상실)를 기본 상황으로 쓴다.
 */
class LifecycleRuleTest {

	private static final List<LoanEvent> G2_DEFAULTED = payScheduled(G2, 2);

	private static Money claim(SimulationResult r) {
		return r.principalBalance().plus(r.outstandingNormalInterest()).plus(r.outstandingLateInterest())
				.plus(r.cost()).plus(r.preAcqInterest());
	}

	private static LoanEvent.ChargeOff chargeOff(String on) {
		return new LoanEvent.ChargeOff("CO", parse(on));
	}

	@Test
	void G2는_08_13에_기한이익상실된다() {
		var r = simulate(G2, G2_DEFAULTED, "2026-09-01");
		assertThat(r.acceleratedOn()).isEqualTo(parse("2026-08-13"));
	}

	// ---------------------------------------------------------------- R39 상각

	@Test
	void R39_상각은_확정일까지_이자를_정산하고_장부_제거액을_남기되_청구_잔액은_유지한다() {
		var before = simulate(G2, G2_DEFAULTED, "2026-09-01");
		var r = simulate(G2, concat(G2_DEFAULTED, List.of(chargeOff("2026-09-01"))), "2026-09-01");

		assertThat(r.status()).isEqualTo(AccountStatus.CHARGED_OFF);
		assertThat(r.chargedOffOn()).isEqualTo(parse("2026-09-01"));
		Posting co = posting(r, "CO");
		assertThat(co.type()).isEqualTo(PostingType.CHARGE_OFF);
		assertThat(co.principal()).isEqualTo(Money.won(10_000_000));
		assertThat(co.amount()).isEqualTo(claim(before));
		// 정산 이벤트이므로 그날까지 이자가 확정된다 (R1)
		assertThat(co.normalAccrued()).isEqualTo(before.accruedNormalInterest());
		assertThat(co.principalBalanceAfter()).isEqualTo(Money.won(10_000_000));
		assertThat(r.principalBalance()).isEqualTo(Money.won(10_000_000));
	}

	@Test
	void R39_상각일부터는_이자가_붙지_않는다() {
		var atChargeOff = simulate(G2, concat(G2_DEFAULTED, List.of(chargeOff("2026-09-01"))), "2026-09-01");
		var later = simulate(G2, concat(G2_DEFAULTED, List.of(chargeOff("2026-09-01"))), "2026-12-01");
		assertThat(later.accruedNormalInterest()).isEqualTo(Money.ZERO);
		assertThat(later.accruedLateInterest()).isEqualTo(Money.ZERO);
		assertThat(claim(later)).isEqualTo(claim(atChargeOff));
	}

	@Test
	void R39_상각_후_입금은_충당_순서대로_회수하고_전액_회수하면_완제() {
		var base = concat(G2_DEFAULTED, List.of(chargeOff("2026-09-01")));
		Money total = claim(simulate(G2, base, "2026-09-01"));
		var partial = simulate(G2, concat(base, List.of(pay("REC", "2026-10-05", 500_000))), "2026-10-05");
		Posting rec = posting(partial, "REC");
		assertThat(rec.type()).isEqualTo(PostingType.REPAYMENT);
		assertThat(rec.normalAccrued()).isEqualTo(Money.ZERO);
		assertThat(claim(partial)).isEqualTo(total.minus(Money.won(500_000)));
		assertThat(partial.status()).isEqualTo(AccountStatus.CHARGED_OFF);

		var full = simulate(G2, concat(base, List.of(pay("REC", "2026-10-05", total.won()))), "2026-10-05");
		assertThat(full.status()).isEqualTo(AccountStatus.PAID_OFF);
	}

	@Test
	void R39_기한이익상실_전이면_상각하지_않는다() {
		var r = simulate(G2, concat(G2_DEFAULTED, List.of(chargeOff("2026-08-01"))), "2026-08-05");
		assertThat(r.chargedOffOn()).isNull();
		assertThat(r.warnings()).anyMatch(w -> w.startsWith("CO:"));
	}

	// ---------------------------------------------------------------- R43 상각 취소 = 이벤트 취소 후 재생

	@Test
	void R43_상각을_취소하면_상각_기간_이자를_되살려_처음부터_상각이_없던_것과_같아진다() {
		var chargedOff = simulate(G2, concat(G2_DEFAULTED, List.of(chargeOff("2026-09-01"))), "2026-12-01");
		// 취소된 이벤트는 재생 입력에서 빠진다 (AccountEvent.status = CANCELLED)
		var reverted = simulate(G2, G2_DEFAULTED, "2026-12-01");
		assertThat(reverted.postings()).noneMatch(p -> p.type() == PostingType.CHARGE_OFF);
		assertThat(reverted.status()).isEqualTo(AccountStatus.ACCELERATED);
		// 상각 기간 [09-01, 12-01)의 이자가 되살아난다: 91일 × 전체 잔액 × 연체이율 18%
		Money revived = InterestRate.of("15.0").interest(Money.won(10_000_000), 91)
				.plus(InterestRate.of("3.0").interest(Money.won(10_000_000), 91));
		assertThat(claim(reverted).minus(claim(chargedOff)).minus(revived).won()).isBetween(-2L, 2L);
	}

	// ---------------------------------------------------------------- R40 매각·매각 후 입금 이전

	private static final LoanEvent.Sale SALE = new LoanEvent.Sale("SALE", parse("2026-09-01"));

	@Test
	void R40_매각하면_잔액을_양수인에게_넘기고_매각일_이후_입금은_이전_대상으로_남긴다() {
		Money atSale = claim(simulate(G2, G2_DEFAULTED, "2026-09-01"));
		var events = concat(G2_DEFAULTED, List.of(SALE, pay("AFTER", "2026-09-01", 300_000),
				pay("AFTER2", "2026-09-10", 200_000)));
		var r = simulate(G2, events, "2026-10-01");

		assertThat(r.status()).isEqualTo(AccountStatus.SOLD);
		assertThat(r.soldOn()).isEqualTo(parse("2026-09-01"));
		Posting sale = posting(r, "SALE");
		assertThat(sale.type()).isEqualTo(PostingType.SALE);
		assertThat(sale.amount()).isEqualTo(atSale);
		assertThat(sale.principal()).isEqualTo(Money.won(10_000_000));
		assertThat(sale.principalBalanceAfter()).isEqualTo(Money.ZERO);
		assertThat(r.principalBalance()).isEqualTo(Money.ZERO);
		assertThat(r.payoffAmount()).isEqualTo(Money.ZERO);
		assertThat(r.accruedNormalInterest()).isEqualTo(Money.ZERO);
		// 매각일 당일 입금부터 이전 대상 (충당하지 않음)
		assertThat(posting(r, "AFTER").type()).isEqualTo(PostingType.SALE_TRANSFER);
		assertThat(posting(r, "AFTER").principal()).isEqualTo(Money.ZERO);
		assertThat(r.transferPending()).isEqualTo(Money.won(500_000));
		assertThat(r.paidOffOn()).isNull();
	}

	@Test
	void R40_양수인에게_넘기면_이전_대상이_줄어든다() {
		var events = concat(G2_DEFAULTED, List.of(SALE, pay("AFTER", "2026-09-10", 300_000),
				new LoanEvent.BuyerTransfer("XFER", parse("2026-09-15"), Money.won(300_000))));
		var r = simulate(G2, events, "2026-10-01");
		assertThat(r.transferPending()).isEqualTo(Money.ZERO);
		assertThat(posting(r, "XFER").amount()).isEqualTo(Money.won(-300_000));
	}

	@Test
	void R40_기한이익상실_전_채권은_매각하지_않는다() {
		var r = simulate(G2, concat(G2_DEFAULTED, List.of(new LoanEvent.Sale("SALE", parse("2026-08-01")))),
				"2026-08-05");
		assertThat(r.soldOn()).isNull();
		assertThat(r.status()).isNotEqualTo(AccountStatus.SOLD);
	}

	// ---------------------------------------------------------------- R41 환매

	@Test
	void R41_환매하면_넘긴_잔액을_되살리고_매각_기간_입금을_환매일에_충당한다() {
		Money atSale = claim(simulate(G2, G2_DEFAULTED, "2026-09-01"));
		var events = concat(G2_DEFAULTED, List.of(SALE, pay("AFTER", "2026-09-10", 300_000),
				new LoanEvent.Repurchase("BUY", parse("2026-10-01"))));
		var r = simulate(G2, events, "2026-10-01");

		assertThat(r.status()).isEqualTo(AccountStatus.ACCELERATED);
		assertThat(r.soldOn()).isNull();
		Posting buy = posting(r, "BUY");
		assertThat(buy.type()).isEqualTo(PostingType.REPURCHASE);
		assertThat(buy.amount()).isEqualTo(Money.ZERO.minus(atSale));
		// 매각 기간 [09-01, 10-01)에는 이자가 없고, 매각 기간 입금 300,000원을 환매일에 충당
		Posting applied = posting(r, "AFTER-BUYBACK");
		assertThat(applied.type()).isEqualTo(PostingType.REPAYMENT);
		assertThat(applied.normalAccrued()).isEqualTo(Money.ZERO);
		assertThat(claim(r)).isEqualTo(atSale.minus(Money.won(300_000)));
		assertThat(r.transferPending()).isEqualTo(Money.ZERO);
	}

	@Test
	void R41_상각_후_매각한_채권을_환매하면_상각_상태로_돌아간다() {
		var events = concat(G2_DEFAULTED, List.of(chargeOff("2026-09-01"),
				new LoanEvent.Sale("SALE", parse("2026-09-02")), new LoanEvent.Repurchase("BUY", parse("2026-10-01"))));
		var r = simulate(G2, events, "2026-11-01");
		assertThat(r.status()).isEqualTo(AccountStatus.CHARGED_OFF);
		assertThat(r.accruedNormalInterest()).isEqualTo(Money.ZERO);
	}

	@Test
	void R41_환매_후에는_다시_이자가_붙는다() {
		var events = concat(G2_DEFAULTED, List.of(SALE, new LoanEvent.Repurchase("BUY", parse("2026-10-01"))));
		var r = simulate(G2, events, "2026-11-01");
		// 10-01부터 31일, 상실 후 전체 잔액 × 연체이율 (정상 15% + 가산 3%)
		assertThat(r.accruedNormalInterest()).isEqualTo(InterestRate.of("15.0").interest(Money.won(10_000_000), 31));
		assertThat(r.accruedLateInterest()).isEqualTo(InterestRate.of("3.0").interest(Money.won(10_000_000), 31));
	}

	// ---------------------------------------------------------------- R42 수기 조정 거래

	@Test
	void R42_수기_조정은_미납액까지만_줄이고_원금은_마지막_회차부터_차감한다() {
		var probe = simulate(G1, payScheduled(G1, 3), "2026-04-20");
		var adj = new LoanEvent.Adjustment("ADJ", parse("2026-04-20"), Money.won(100_000), Money.ZERO,
				Money.won(999_999_999), Money.won(50_000), Money.ZERO);
		var r = simulate(G1, concat(payScheduled(G1, 3), List.of(adj)), "2026-04-20");
		Posting p = posting(r, "ADJ");
		assertThat(p.type()).isEqualTo(PostingType.ADJUSTMENT);
		assertThat(p.principal()).isEqualTo(Money.won(100_000));
		assertThat(p.lateInterest()).isEqualTo(probe.outstandingLateInterest());
		assertThat(p.cost()).isEqualTo(Money.won(-50_000));
		assertThat(p.allocations()).first().extracting(Posting.InstallmentAllocation::installmentSeq).isEqualTo(12);
		assertThat(r.cost()).isEqualTo(Money.won(50_000));
		assertThat(r.principalBalance()).isEqualTo(probe.principalBalance().minus(Money.won(100_000)));
		// 다음 회차(4회차) 원금은 그대로 → 연체가 새로 생기지 않는다
		assertThat(r.installment(4).orElseThrow().principalDue())
				.isEqualTo(probe.installment(4).orElseThrow().principalDue());
	}

	@Test
	void R42_전액을_감액하면_완제된다() {
		var probe = simulate(G2, G2_DEFAULTED, "2026-09-01");
		var adj = new LoanEvent.Adjustment("ADJ", parse("2026-09-01"), probe.principalBalance(),
				Money.won(999_999_999), Money.won(999_999_999), Money.ZERO, Money.ZERO);
		var r = simulate(G2, concat(G2_DEFAULTED, List.of(adj)), "2026-09-01");
		assertThat(r.status()).isEqualTo(AccountStatus.PAID_OFF);
	}

	// ---------------------------------------------------------------- R44 상계

	@Test
	void R44_반환_대기_과입금을_상계하면_원천_계약의_반환_대기가_줄어든다() {
		var payoff = simulate(G2, List.of(), "2026-04-01").payoffAmount();
		var events = List.<LoanEvent>of(pay("OVER", "2026-04-01", payoff.won() + 70_000),
				new LoanEvent.PrepaymentRequest("OVER-PRE", parse("2026-04-01"), null),
				new LoanEvent.SetoffOut("SET", parse("2026-04-10"), Money.won(50_000), LoanEvent.SetoffSource.REFUND));
		var r = simulate(G2, events, "2026-04-10");
		assertThat(r.status()).isEqualTo(AccountStatus.PAID_OFF);
		assertThat(r.refundPending()).isEqualTo(Money.won(20_000));
		assertThat(posting(r, "SET").refund()).isEqualTo(Money.won(-50_000));
	}

	@Test
	void R44_상계를_받은_연체_계약은_입금처럼_즉시_충당하지만_변제_입금은_아니다() {
		var r = simulate(G2, concat(G2_DEFAULTED,
				List.of(new LoanEvent.SetoffIn("SET", parse("2026-09-01"), Money.won(200_000)))), "2026-09-01");
		Posting p = posting(r, "SET");
		assertThat(p.type()).isEqualTo(PostingType.SETOFF);
		assertThat(p.lateInterest().plus(p.normalInterest()).plus(p.principal())).isEqualTo(Money.won(200_000));
	}

	@Test
	void R44_연체_없는_계약이_상계를_받으면_가수금으로_보관한다() {
		var r = simulate(G1, List.of(new LoanEvent.SetoffIn("SET", parse("2026-02-01"), Money.won(100_000))),
				"2026-02-01");
		assertThat(posting(r, "SET").suspense()).isEqualTo(Money.won(100_000));
		assertThat(r.suspense()).isEqualTo(Money.won(100_000));
	}

	// ---------------------------------------------------------------- R46 이자 부과 중단

	@Test
	void R46_이자_부과_중단_기간에는_정상이자와_가산이자를_붙이지_않는다() {
		var stop = new LoanEvent.InterestSuspension("STOP", parse("2026-09-01"), parse("2026-10-01"));
		var r = simulate(G2, concat(G2_DEFAULTED, List.of(stop)), "2026-10-11");
		var base = simulate(G2, G2_DEFAULTED, "2026-10-11");
		Money normal30 = InterestRate.of("15.0").interest(Money.won(10_000_000), 30);
		Money late30 = InterestRate.of("3.0").interest(Money.won(10_000_000), 30);
		// 구간 절사 차이(R2)로 1원 이내
		assertThat(base.accruedNormalInterest().minus(r.accruedNormalInterest()).minus(normal30).won())
				.isBetween(-1L, 1L);
		assertThat(base.accruedLateInterest().minus(r.accruedLateInterest()).minus(late30).won()).isBetween(-1L,
				1L);
	}

	@Test
	void R46_끝이_없는_중단은_계속_이자를_붙이지_않는다() {
		var stop = new LoanEvent.InterestSuspension("STOP", parse("2026-09-01"), null);
		var at = simulate(G2, concat(G2_DEFAULTED, List.of(stop)), "2026-09-01");
		var r = simulate(G2, concat(G2_DEFAULTED, List.of(stop)), "2027-03-01");
		assertThat(claim(r)).isEqualTo(claim(at));
	}

	@Test
	void R46_매입채권도_양수일_뒤_금리_이력으로_적용이율이_바뀐다() {
		var terms = Golden.g17(true);
		var lowered = terms.withRatePeriods(List.of(new RatePeriod(parse("2026-04-01"), InterestRate.of("5.0"))));
		assertThat(lowered.rateAt(parse("2026-03-31"))).isEqualTo(terms.rateAt(parse("2026-03-31")));
		assertThat(lowered.rateAt(parse("2026-04-01"))).isEqualTo(InterestRate.of("5.0"));
		var r = simulate(lowered, List.of(), "2026-05-01");
		var base = simulate(terms, List.of(), "2026-05-01");
		assertThat(r.accruedNormalInterest()).isLessThan(base.accruedNormalInterest());
	}

	@Test
	void 생애주기_이벤트가_있어도_재생은_결정적이다() {
		var events = concat(G2_DEFAULTED, List.of(chargeOff("2026-09-01"), SALE,
				pay("AFTER", "2026-09-10", 300_000), new LoanEvent.Repurchase("BUY", parse("2026-10-01"))));
		assertThat(simulate(G2, events, "2026-11-01")).isEqualTo(simulate(G2, events, "2026-11-01"));
	}
}
