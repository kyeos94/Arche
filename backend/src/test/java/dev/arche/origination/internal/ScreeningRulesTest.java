package dev.arche.origination.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arche.integration.CreditBureauPort;
import dev.arche.origination.internal.ScreeningRules.Outcome;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 정책서 7장 자동 거절 + R53 */
class ScreeningRulesTest {

	static final LocalDate TODAY = LocalDate.of(2026, 11, 1);

	@ParameterizedTest(name = "생년월일 {0} → {1}")
	@CsvSource({ "2007-11-01, PASS", "2007-11-02, REJECT", "1980-01-01, PASS", "2010-01-01, REJECT" })
	void 만_19세는_업무일자_기준_만_나이로_판정한다(LocalDate birth, Outcome expected) {
		assertThat(ScreeningRules.minAge(birth, TODAY).outcome()).isEqualTo(expected);
	}

	@ParameterizedTest(name = "거절일 {0} → {1}")
	@CsvSource({ "2026-11-01, REJECT", "2026-10-03, REJECT", "2026-10-02, PASS", "2026-09-01, PASS" })
	void 최근_30일은_직전_30일_당일_포함이다(LocalDate rejectedOn, Outcome expected) {
		assertThat(ScreeningRules.recentRejection(List.of(rejectedOn), TODAY).outcome()).isEqualTo(expected);
	}

	@Test
	void 진행_중인_다른_신청이_있으면_중복_신청으로_거절() {
		assertThat(ScreeningRules.duplicate(List.of()).outcome()).isEqualTo(Outcome.PASS);
		var r = ScreeningRules.duplicate(List.of("A00001001"));
		assertThat(r.outcome()).isEqualTo(Outcome.REJECT);
		assertThat(r.detail()).contains("A00001001");
	}

	@Test
	void 불량_상태_계약이_있으면_내부_불량_이력으로_거절() {
		assertThat(ScreeningRules.internalHistory(List.of()).outcome()).isEqualTo(Outcome.PASS);
		assertThat(ScreeningRules.internalHistory(List.of("L00001001 ACCELERATED")).outcome())
				.isEqualTo(Outcome.REJECT);
	}

	@Test
	void 신용조회는_동의가_있고_앞_규칙을_통과했을_때만_판정한다() {
		var clean = new CreditBureauPort.Report("R", 3, false, 1, 3_000_000);
		var delinquent = new CreditBureauPort.Report("R", 9, true, 1, 3_000_000);
		assertThat(ScreeningRules.creditBureau(true, true, clean).outcome()).isEqualTo(Outcome.SKIPPED);
		assertThat(ScreeningRules.creditBureau(false, false, null).outcome()).isEqualTo(Outcome.SKIPPED);
		assertThat(ScreeningRules.creditBureau(false, true, clean).outcome()).isEqualTo(Outcome.PASS);
		assertThat(ScreeningRules.creditBureau(false, true, delinquent).outcome()).isEqualTo(Outcome.REJECT);
	}

	@Test
	void 조회_전_규칙은_모두_판정해_결과를_남긴다() {
		var results = ScreeningRules.beforeCreditBureau(LocalDate.of(2010, 1, 1), TODAY, List.of("A1"),
				List.of(TODAY), List.of());
		assertThat(results).extracting(ScreeningRules.Result::outcome).containsExactly(Outcome.REJECT,
				Outcome.REJECT, Outcome.REJECT, Outcome.PASS);
		assertThat(ScreeningRules.anyRejected(results)).isTrue();
	}

	@Test
	void 신용조회_동의는_동의일부터_1년_유효하고_철회하면_무효() {
		ApplicationConsent c = new ApplicationConsent(1L, ApplicationConsent.Type.CREDIT_INQUIRY,
				ApplicationConsent.Channel.STAFF, TODAY, null);
		assertThat(c.validUntil()).isEqualTo(LocalDate.of(2027, 10, 31));
		assertThat(c.validOn(TODAY.minusDays(1))).isFalse();
		assertThat(c.validOn(LocalDate.of(2027, 10, 31))).isTrue();
		assertThat(c.validOn(LocalDate.of(2027, 11, 1))).isFalse();
		c.withdraw(java.time.OffsetDateTime.parse("2026-11-02T10:00:00+09:00"));
		assertThat(c.validOn(TODAY)).isFalse();
	}
}
