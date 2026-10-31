package dev.arche.servicing.engine;

import static dev.arche.servicing.engine.Golden.G2;
import static dev.arche.servicing.engine.Golden.G3;
import static dev.arche.servicing.engine.Golden.G4;
import static dev.arche.servicing.engine.Golden.SIMULATOR;
import static dev.arche.servicing.engine.Golden.concat;
import static dev.arche.servicing.engine.Golden.pay;
import static dev.arche.servicing.engine.Golden.payScheduled;
import static dev.arche.servicing.engine.Golden.posting;
import static dev.arche.servicing.engine.Golden.simulate;
import static java.time.LocalDate.parse;
import static org.assertj.core.api.Assertions.assertThat;

import dev.arche.common.Money;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * golden 사례 밖의 정책 분기 (정책서 9~10장).
 */
class PolicyRuleTest {

	@Test
	void R3_실제_도달일이_확인되면_상실일을_다시_계산한다() {
		var known = List.of(new KnownNotice(parse("2026-07-28"), parse("2026-08-03")));
		var r = SIMULATOR.simulate(G2, payScheduled(G2, 2), parse("2026-08-25"),
				new LoanSimulator.Options(null, known));
		// 도달 08-03(월) 다음 영업일부터 10번째 = 08-18 (08-17 공휴일 제외)
		assertThat(r.notices()).singleElement().satisfies(n -> {
			assertThat(n.actualArrivalOn()).isEqualTo(parse("2026-08-03"));
			assertThat(n.accelerationDate()).isEqualTo(parse("2026-08-18"));
		});
		assertThat(r.acceleratedOn()).isEqualTo(parse("2026-08-18"));
	}

	@Test
	void 상실일_전에_연체를_모두_해소하면_통지_절차를_취소한다() {
		// 3·4회차 미납 후 08-05에 연체금 전액 납부 (G8 상황, 상실 예정 08-13)
		var probe = simulate(G2, payScheduled(G2, 2), "2026-08-05");
		var events = concat(payScheduled(G2, 2), List.of(pay("CURE", "2026-08-05", probe.payoffAmount().won())));
		var r = simulate(G2, events, "2026-08-20");
		assertThat(r.notices()).singleElement().satisfies(n -> {
			assertThat(n.status()).isEqualTo(NoticeStatus.CANCELLED);
			assertThat(n.closedOn()).isEqualTo(parse("2026-08-06"));
		});
		assertThat(r.status()).isNotEqualTo(AccountStatus.ACCELERATED);
	}

	@Test
	void R5_소급_재계산으로_사유가_없어지면_저장된_통지는_무효가_된다() {
		var known = List.of(new KnownNotice(parse("2026-07-28"), null));
		// 3회차(06-25)를 기한 안에 냈던 것으로 소급 매칭 → 2회차 연속 연체가 처음부터 없음
		var events = concat(payScheduled(G2, 3));
		var r = SIMULATOR.simulate(G2, events, parse("2026-08-25"), new LoanSimulator.Options(null, known));
		assertThat(r.notices()).singleElement().extracting(AccelerationNoticeView::status)
				.isEqualTo(NoticeStatus.VOID);
		assertThat(r.status()).isEqualTo(AccountStatus.DELINQUENT);
	}

	@Test
	void R29_취소로_과거에_생겼어야_할_사유는_처리일을_사유_발생일로_본다() {
		var r = SIMULATOR.simulate(G2, payScheduled(G2, 2), parse("2026-09-01"),
				new LoanSimulator.Options(parse("2026-08-20"), List.of()));
		assertThat(r.notices()).singleElement().satisfies(n -> {
			assertThat(n.reasonDate()).isEqualTo(parse("2026-08-20"));
			assertThat(n.status()).isEqualTo(NoticeStatus.PENDING);
		});
		assertThat(r.status()).isEqualTo(AccountStatus.DELINQUENT);
	}

	@Test
	void R32_만기일시_약정일_전_입금은_가수금으로_보관했다가_약정일에_충당한다() {
		var r = simulate(G3, List.of(pay("EARLY", "2026-06-10", 100_000)), "2026-06-15");
		assertThat(posting(r, "EARLY").type()).isEqualTo(PostingType.SUSPENSE_IN);
		Posting applied = posting(r, "DUE-2026-06-15");
		assertThat(applied.normalInterest()).isEqualTo(Money.won(80_684));
		assertThat(r.suspense()).isEqualTo(Money.won(100_000 - 80_684));
		assertThat(r.installment(1).orElseThrow().status()).isEqualTo(InstallmentStatus.PAID);
	}

	@Test
	void R33_거치_회차_이자_미납도_2회차_연속이면_상실_사유() {
		var r = simulate(G4, List.of(), "2026-03-20");
		// 1회차 02-05, 2회차 03-05 → 03-06이 사유 발생일
		assertThat(r.notices()).singleElement().extracting(AccelerationNoticeView::reasonDate)
				.isEqualTo(parse("2026-03-06"));
	}

	@Test
	void 재생은_결정적이다() {
		var events = concat(payScheduled(G2, 1), List.of(pay("U", "2026-05-26", 1_135_616)));
		assertThat(simulate(G2, events, "2026-12-31")).isEqualTo(simulate(G2, events, "2026-12-31"));
	}
}
