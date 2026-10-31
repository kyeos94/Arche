package dev.arche.common;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * 연이율(%). 예: 19.9 = 연 19.9%.
 */
public record InterestRate(BigDecimal percent) implements Comparable<InterestRate> {

	public static final InterestRate ZERO = new InterestRate(BigDecimal.ZERO);
	/** 법정 최고이자율 (대부업법 시행령, 정책서 3장 연체이율 상한) */
	public static final InterestRate LEGAL_MAX = InterestRate.of("20.0");
	/** 연 일수 365 고정 (윤년도 365) */
	public static final int DAYS_IN_YEAR = 365;

	public InterestRate {
		if (percent.signum() < 0) {
			throw new IllegalArgumentException("음수 이율: " + percent);
		}
		percent = percent.stripTrailingZeros();
	}

	public static InterestRate of(String percent) {
		return new InterestRate(new BigDecimal(percent));
	}

	public InterestRate plus(InterestRate other) {
		return new InterestRate(percent.add(other.percent));
	}

	public InterestRate minus(InterestRate other) {
		return new InterestRate(percent.subtract(other.percent).max(BigDecimal.ZERO));
	}

	public InterestRate min(InterestRate other) {
		return compareTo(other) <= 0 ? this : other;
	}

	public boolean isZero() {
		return percent.signum() == 0;
	}

	/** 연체이율 = min(약정이율 + 3.0%p, 20.0%) (정책서 3장) */
	public InterestRate lateRate() {
		return plus(InterestRate.of("3.0")).min(LEGAL_MAX);
	}

	/** 가산분 = 연체이율 − 약정이율 */
	public InterestRate lateSpread() {
		return lateRate().minus(this);
	}

	/** 일할 이자: 금액 × 연이율 × 일수 ÷ 365, 원 미만 절사 */
	public Money interest(Money base, long days) {
		if (days <= 0 || base.isZero() || isZero()) {
			return Money.ZERO;
		}
		BigDecimal raw = base.toBigDecimal().multiply(percent).multiply(BigDecimal.valueOf(days))
				.divide(BigDecimal.valueOf(100L * DAYS_IN_YEAR), MathContext.DECIMAL128);
		return Money.floor(raw);
	}

	/** 월이율 (연이율 ÷ 12), 원리금균등 공식용 */
	public BigDecimal monthlyFraction() {
		return percent.divide(BigDecimal.valueOf(1200), MathContext.DECIMAL128);
	}

	@Override
	public int compareTo(InterestRate o) {
		return percent.compareTo(o.percent);
	}

	@Override
	public String toString() {
		return percent.toPlainString() + "%";
	}
}
