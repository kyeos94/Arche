package dev.arche.origination.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.arche.common.BusinessException;
import dev.arche.common.InterestRate;
import dev.arche.common.Money;
import dev.arche.common.RepaymentMethod;
import dev.arche.integration.CreditBureauPort;
import dev.arche.product.ProductView;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** R56 중개수수료, R57 플랫폼 가심사, R58 랜딩 링크 */
class ChannelRulesTest {

	static final LocalDate D = LocalDate.of(2026, 11, 2);

	@ParameterizedTest(name = "{0}원 × {1}% = {2}원")
	@CsvSource({ "10000000, 3.00, 300000", "3333333, 2.50, 83333", "5000000, 0, 0", "1234567, 1.75, 21604" })
	void 수수료는_실행금액_곱하기_수수료율_원_미만_절사(long base, String rate, long expected) {
		assertThat(CommissionRules.commission(Money.won(base), new BigDecimal(rate)).won()).isEqualTo(expected);
	}

	@Test
	void 수수료율은_실행일에_유효한_이력에서_반개구간으로_찾는다() {
		var a = new BrokerCommissionRate(1, new BigDecimal("3.00"), LocalDate.of(2026, 1, 1), "등록");
		a.closeAt(LocalDate.of(2026, 11, 2));
		var b = new BrokerCommissionRate(1, new BigDecimal("2.50"), LocalDate.of(2026, 11, 2), "인하");
		assertThat(CommissionRules.rateOn(List.of(a, b), LocalDate.of(2026, 11, 1))).hasValue(new BigDecimal("3.00"));
		assertThat(CommissionRules.rateOn(List.of(a, b), D)).hasValue(new BigDecimal("2.50"));
		assertThat(CommissionRules.rateOn(List.of(a, b), LocalDate.of(2025, 12, 31))).isEmpty();
	}

	@ParameterizedTest(name = "실행 2026-11-02, 완제 {0} → 환수 {1}")
	@CsvSource({ "2026-11-02, true", "2027-02-01, true", "2027-02-02, false" })
	void 조기_완제_환수는_실행일부터_3개월_안(LocalDate paidOff, boolean clawback) {
		assertThat(CommissionRules.earlyPayoffClawback(D, paidOff)).isEqualTo(clawback);
	}

	@ParameterizedTest(name = "실행 2026-11-02, 확인 {0} → 환수 가능 {1}")
	@CsvSource({ "2026-11-02, true", "2027-11-01, true", "2027-11-02, false", "2026-11-01, false" })
	void 불완전판매_환수는_실행일부터_1년_안(LocalDate confirmed, boolean allowed) {
		assertThat(CommissionRules.misSellingClawbackAllowed(D, confirmed)).isEqualTo(allowed);
	}

	@Test
	void 수수료율은_0부터_4퍼센트까지_소수_둘째_자리() {
		CommissionRules.validateRate(new BigDecimal("4.00"));
		assertThatThrownBy(() -> CommissionRules.validateRate(new BigDecimal("4.01")))
				.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> CommissionRules.validateRate(new BigDecimal("-0.1")))
				.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> CommissionRules.validateRate(new BigDecimal("1.255")))
				.isInstanceOf(BusinessException.class);
	}

	static final ProductView P1 = new ProductView(1, "P1", "개인신용 분할", RepaymentMethod.EQUAL_INSTALLMENT,
			InterestRate.of("19.9"), Money.won(1_000_000), Money.won(30_000_000), 12, 60, 0, 0, "NONE",
			InterestRate.of("1.0"), 1);

	static CreditBureauPort.Report report(int grade) {
		return new CreditBureauPort.Report("R", grade, grade >= 9, 0, 0);
	}

	@ParameterizedTest(name = "등급 {0} → 한도 {1}")
	@CsvSource({ "1, 30000000", "3, 30000000", "4, 18000000", "6, 18000000", "7, 9000000", "8, 9000000" })
	void 한도는_상품_최대_한도_곱하기_등급_계수(int grade, long limit) {
		PlatformOffer.Offer o = PlatformOffer.evaluate(P1, LocalDate.of(1990, 1, 1), D, report(grade));
		assertThat(o.offered()).isTrue();
		assertThat(o.limit().won()).isEqualTo(limit);
		assertThat(o.rate()).isEqualTo(InterestRate.of("19.9"));
	}

	@Test
	void 미성년_연체_9등급_이하는_한도를_제시하지_않는다() {
		assertThat(PlatformOffer.evaluate(P1, LocalDate.of(2010, 1, 1), D, report(1)).offered()).isFalse();
		assertThat(PlatformOffer.evaluate(P1, LocalDate.of(1990, 1, 1), D, report(9)).declineReason())
				.contains("연체");
		assertThat(PlatformOffer.evaluate(P1, LocalDate.of(1990, 1, 1), D,
				new CreditBureauPort.Report("R", 9, false, 0, 0)).declineReason()).contains("등급");
		assertThat(PlatformOffer.evaluate(P1, LocalDate.of(1990, 1, 1), D, null).offered()).isFalse();
	}

	@Test
	void 한도조회는_조회일부터_7일_유효() {
		var q = new PlatformInquiry("Q1", "MOCKPLAT", "R1", "bidx", "P1", 1, 12, "c", null, 1,
				PlatformOffer.evaluate(P1, LocalDate.of(1990, 1, 1), D, report(1)), D);
		assertThat(q.validUntil()).isEqualTo(LocalDate.of(2026, 11, 8));
	}

	@Test
	void 랜딩_링크는_3회_불일치면_폐기되고_만료_후에는_쓸_수_없다() {
		OffsetDateTime now = OffsetDateTime.parse("2026-11-02T10:00:00+09:00");
		var v = new IdentityVerification(1, 1, "h", now.plus(LandingService.TTL));
		assertThat(v.failed()).isEqualTo(2);
		assertThat(v.failed()).isEqualTo(1);
		v.requireUsable(now);
		assertThat(v.failed()).isZero();
		assertThatThrownBy(() -> v.requireUsable(now)).isInstanceOf(BusinessException.class);

		var w = new IdentityVerification(1, 1, "h2", now.plus(LandingService.TTL));
		assertThatThrownBy(() -> w.requireVerified(now)).as("본인인증 전").isInstanceOf(BusinessException.class);
		w.verified("ci", now);
		w.requireVerified(now.plusMinutes(29));
		assertThatThrownBy(() -> w.requireUsable(now.plusMinutes(30))).as("30분 만료")
				.isInstanceOf(BusinessException.class);
		w.revoke();
		assertThat(w.status()).isEqualTo(IdentityVerification.Status.REVOKED);
	}

	@Test
	void 고객_이름은_첫_글자와_끝_글자만_보인다() {
		assertThat(LandingService.maskName("김고객")).isEqualTo("김*객");
		assertThat(LandingService.maskName("남궁길동")).isEqualTo("남**동");
		assertThat(LandingService.maskName("이영")).isEqualTo("이*");
	}

	@Test
	void 중개업자_IP_허용_목록이_비면_제한하지_않는다() {
		var open = new Broker("B1", "가상중개", "R-1", "h", " ");
		assertThat(open.allows("10.0.0.1")).isTrue();
		var limited = new Broker("B2", "가상중개2", "R-2", "h2", "127.0.0.1, 10.0.0.2");
		assertThat(limited.allows("10.0.0.2")).isTrue();
		assertThat(limited.allows("10.0.0.3")).isFalse();
		assertThat(BrokerService.newKey()).startsWith("brk_").hasSizeGreaterThan(30);
	}
}
