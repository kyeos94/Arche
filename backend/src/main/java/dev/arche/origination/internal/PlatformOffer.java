package dev.arche.origination.internal;

import dev.arche.common.InterestRate;
import dev.arche.common.Money;
import dev.arche.integration.CreditBureauPort;
import dev.arche.product.ProductView;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import org.jspecify.annotations.Nullable;

/**
 * 대출 비교 플랫폼 1단계 한도조회의 가심사 (R57). 순수 함수.
 * 한도 = 상품 최대 한도 × 등급 계수(1~3등급 100%, 4~6등급 60%, 7~8등급 30%), 원 미만 절사.
 * 만 19세 미만·현재 연체·9등급 이하·한도가 상품 최소 한도 미만이면 제시하지 않는다. 제시 금리는 상품 기본 금리(인하는 본심사에서만).
 */
final class PlatformOffer {

	/** 한도조회 결과 유효기간: 조회일부터 7일 (당일 포함) */
	static final int VALID_DAYS = 7;

	record Offer(boolean offered, @Nullable Money limit, @Nullable InterestRate rate, @Nullable String declineReason) {

		static Offer decline(String reason) {
			return new Offer(false, null, null, reason);
		}
	}

	private PlatformOffer() {
	}

	static BigDecimal factor(int grade) {
		if (grade <= 3) {
			return BigDecimal.ONE;
		}
		if (grade <= 6) {
			return new BigDecimal("0.6");
		}
		if (grade <= 8) {
			return new BigDecimal("0.3");
		}
		return BigDecimal.ZERO;
	}

	static Offer evaluate(ProductView product, LocalDate birthDate, LocalDate today,
			CreditBureauPort.@Nullable Report report) {
		if (Period.between(birthDate, today).getYears() < ScreeningRules.MIN_AGE) {
			return Offer.decline("만 19세 미만");
		}
		if (report == null) {
			return Offer.decline("신용조회 불가");
		}
		if (report.currentlyDelinquent()) {
			return Offer.decline("신용조회 현재 연체");
		}
		BigDecimal f = factor(report.grade());
		if (f.signum() == 0) {
			return Offer.decline("신용 등급 기준 미달");
		}
		Money limit = Money.floor(product.maxAmount().toBigDecimal().multiply(f));
		if (limit.isLessThan(product.minAmount())) {
			return Offer.decline("한도가 상품 최소 금액 미만");
		}
		return new Offer(true, limit, product.defaultRate(), null);
	}
}
