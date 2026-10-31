package dev.arche.product;

import dev.arche.common.BusinessException;
import dev.arche.common.InterestRate;
import dev.arche.common.Money;
import dev.arche.common.RepaymentMethod;

public record ProductView(
		long id,
		String code,
		String name,
		RepaymentMethod repaymentMethod,
		InterestRate defaultRate,
		Money minAmount,
		Money maxAmount,
		int minTermMonths,
		int maxTermMonths,
		int minGraceMonths,
		int maxGraceMonths,
		String collateralType,
		InterestRate prepaymentFeeRate,
		int productVersion) {

	/** 심사 시 기본값에서 최대 3.0%p까지 낮출 수 있음, 올리는 것은 불가 (정책서 1장) */
	public static final InterestRate MAX_DISCOUNT = InterestRate.of("3.0");

	/**
	 * 실행 조건 검증. termMonths는 분할 회차 수(거치 제외), graceMonths는 거치 회차 수.
	 */
	public void validate(Money amount, int termMonths, int graceMonths, InterestRate rate) {
		if (amount.isLessThan(minAmount) || amount.isGreaterThan(maxAmount)) {
			throw new BusinessException("PRODUCT_AMOUNT", name + " 한도는 " + minAmount + " ~ " + maxAmount + "입니다");
		}
		if (termMonths < minTermMonths || termMonths > maxTermMonths) {
			throw new BusinessException("PRODUCT_TERM",
					name + " 기간은 " + minTermMonths + " ~ " + maxTermMonths + "개월입니다");
		}
		if (graceMonths < minGraceMonths || graceMonths > maxGraceMonths) {
			throw new BusinessException("PRODUCT_GRACE",
					name + " 거치 기간은 " + minGraceMonths + " ~ " + maxGraceMonths + "개월입니다");
		}
		if (rate.compareTo(defaultRate) > 0 || rate.compareTo(defaultRate.minus(MAX_DISCOUNT)) < 0) {
			throw new BusinessException("PRODUCT_RATE",
					"금리는 " + defaultRate.minus(MAX_DISCOUNT) + " ~ " + defaultRate + " 범위여야 합니다");
		}
	}
}
