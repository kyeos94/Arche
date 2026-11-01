package dev.arche.servicing.internal;

import static java.time.LocalDate.parse;
import static org.assertj.core.api.Assertions.assertThat;

import dev.arche.common.BusinessCalendar;
import dev.arche.common.InterestRate;
import dev.arche.common.Money;
import dev.arche.common.RepaymentMethod;
import dev.arche.servicing.engine.AccountStatus;
import dev.arche.servicing.engine.LoanEvent;
import dev.arche.servicing.engine.LoanSimulator;
import dev.arche.servicing.engine.LoanTerms;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 상각 후보(R39)와 R31 제한·완제 취소(R43) 판정. DB 없이 엔진 재생 결과를 계약 요약에 반영해 검사한다.
 */
class LifecycleServiceTest {

	private static final LoanSimulator SIM = new LoanSimulator(new BusinessCalendar(Set.of()));
	private static final LoanTerms G2 = LoanTerms.originated(RepaymentMethod.EQUAL_PRINCIPAL, 12_000_000,
			parse("2026-03-20"), 12, 0, 25, "15.0", "1.2");

	/** 1·2회차만 납부해 기한이익상실된 계약 */
	private static List<LoanEvent> defaulted() {
		var plan = SIM.simulate(G2, List.of(), G2.disbursedOn());
		List<LoanEvent> events = new ArrayList<>();
		for (var i : plan.installments()) {
			if (i.seq() <= 2) {
				events.add(new LoanEvent.Payment("INST-" + i.seq(), i.dueDate(), i.plannedPayment()));
			}
		}
		return events;
	}

	private static LoanAccount account(List<LoanEvent> events, LocalDate asOf) {
		LoanAccount a = new LoanAccount("L00000001", 1, 1, "P3", 1, null, LoanAccount.Origin.ORIGINATED,
				RepaymentMethod.EQUAL_PRINCIPAL, Money.won(12_000_000), G2.disbursedOn(), 12, 0,
				InterestRate.of("1.2"), null, "99000000001");
		a.apply(SIM.simulate(G2, events, asOf));
		return a;
	}

	@Test
	void R39_기한이익상실_후_180일이_지나야_후보() {
		LoanAccount a = account(defaulted(), parse("2026-09-01"));
		LocalDate acc = a.acceleratedOn();
		assertThat(a.status()).isEqualTo(AccountStatus.ACCELERATED);
		assertThat(LifecycleService.chargeOffBlocker(a, null, acc.plusDays(179))).isPresent();
		assertThat(LifecycleService.chargeOffBlocker(a, null, acc.plusDays(180))).isEmpty();
	}

	@Test
	void R39_기준일_포함_직전_90일_안에_입금이_있으면_후보가_아니다() {
		LoanAccount a = account(defaulted(), parse("2026-09-01"));
		LocalDate today = a.acceleratedOn().plusDays(200);
		assertThat(LifecycleService.chargeOffBlocker(a, today.minusDays(89), today)).isPresent();
		assertThat(LifecycleService.chargeOffBlocker(a, today.minusDays(90), today)).isEmpty();
	}

	@Test
	void R39_기한이익상실이_아니면_후보가_아니다() {
		LoanAccount a = account(List.of(), parse("2026-04-01"));
		assertThat(LifecycleService.chargeOffBlocker(a, null, parse("2027-04-01"))).isPresent();
	}

	@Test
	void R31_매각_계약은_매각일_전_입금만_취소를_막는다() {
		var events = new ArrayList<>(defaulted());
		events.add(new LoanEvent.Sale("SALE", parse("2026-09-01")));
		LoanAccount a = account(events, parse("2026-09-10"));
		assertThat(a.status()).isEqualTo(AccountStatus.SOLD);
		assertThat(LoanAccountService.restrictedByR31(a, parse("2026-08-31"))).isTrue();
		assertThat(LoanAccountService.restrictedByR31(a, parse("2026-09-01"))).isFalse();
	}

	@Test
	void R31_상각_계약은_상각일_전_입금만_취소를_막고_완제는_모두_막는다() {
		var events = new ArrayList<>(defaulted());
		events.add(new LoanEvent.ChargeOff("CO", parse("2026-09-01")));
		LoanAccount a = account(events, parse("2026-09-10"));
		assertThat(a.status()).isEqualTo(AccountStatus.CHARGED_OFF);
		assertThat(LoanAccountService.restrictedByR31(a, parse("2026-08-31"))).isTrue();
		assertThat(LoanAccountService.restrictedByR31(a, parse("2026-09-05"))).isFalse();

		var paid = SIM.simulate(G2, List.of(), parse("2026-04-01")).payoffAmount();
		LoanAccount off = account(List.of(new LoanEvent.Payment("ALL", parse("2026-04-01"), paid),
				new LoanEvent.PrepaymentRequest("ALL-PRE", parse("2026-04-01"), null)), parse("2026-04-02"));
		assertThat(off.status()).isEqualTo(AccountStatus.PAID_OFF);
		assertThat(LoanAccountService.restrictedByR31(off, parse("2026-04-01"))).isTrue();
	}

	@Test
	void R39_상각_청구_잔액은_원금_이자_비용_합계() {
		var events = new ArrayList<>(defaulted());
		events.add(new LoanEvent.ChargeOff("CO", parse("2026-09-01")));
		LoanAccount a = account(events, parse("2026-10-01"));
		assertThat(a.chargedOffOn()).isEqualTo(parse("2026-09-01"));
		assertThat(LifecycleService.claim(a)).isEqualTo(a.principalBalance() + a.unpaidNormalInterest()
				+ a.unpaidLateInterest() + a.costBalance());
	}
}
