package dev.arche.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 원 단위 금액. 원 미만은 존재하지 않으며, 원 미만 처리(절사·반올림)는 이 클래스에서만 한다.
 */
public record Money(long won) implements Comparable<Money> {

	public static final Money ZERO = new Money(0);

	public static Money won(long won) {
		return won == 0 ? ZERO : new Money(won);
	}

	/** 원 미만 절사 (정책서 2장 단수 처리). 음수는 0 방향으로 절사. */
	public static Money floor(BigDecimal amount) {
		return won(amount.setScale(0, RoundingMode.DOWN).longValueExact());
	}

	public Money plus(Money other) {
		return won(Math.addExact(won, other.won));
	}

	public Money minus(Money other) {
		return won(Math.subtractExact(won, other.won));
	}

	public Money min(Money other) {
		return won <= other.won ? this : other;
	}

	public Money max(Money other) {
		return won >= other.won ? this : other;
	}

	/**
	 * 비례 배분 몫: 이 금액 × numerator ÷ denominator, 원 미만 절사 (R47 잔액 비례 배분).
	 */
	public Money share(long numerator, long denominator) {
		if (denominator <= 0 || numerator < 0) {
			throw new IllegalArgumentException("배분 비율이 올바르지 않습니다");
		}
		return floor(toBigDecimal().multiply(BigDecimal.valueOf(numerator))
				.divide(BigDecimal.valueOf(denominator), 0, RoundingMode.DOWN));
	}

	public boolean isZero() {
		return won == 0;
	}

	public boolean isPositive() {
		return won > 0;
	}

	public boolean isNegative() {
		return won < 0;
	}

	public boolean isGreaterThan(Money other) {
		return won > other.won;
	}

	public boolean isLessThan(Money other) {
		return won < other.won;
	}

	public BigDecimal toBigDecimal() {
		return BigDecimal.valueOf(won);
	}

	/** 비율(%) 계산, 소수 둘째 자리에서 반올림해 첫째 자리까지 (정책서 R25 누적 회수율). */
	public BigDecimal percentOf(Money base) {
		if (base.isZero()) {
			return BigDecimal.ZERO;
		}
		return toBigDecimal().multiply(BigDecimal.valueOf(100))
				.divide(base.toBigDecimal(), 1, RoundingMode.HALF_UP);
	}

	@Override
	public int compareTo(Money o) {
		return Long.compare(won, o.won);
	}

	@Override
	public String toString() {
		return won + "원";
	}
}
