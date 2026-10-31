package dev.arche.servicing.engine;

import dev.arche.common.InterestRate;
import dev.arche.common.Money;
import java.time.LocalDate;

/**
 * 매입채권(N1) 양수 조건 (정책서 1-1장, R17).
 *
 * @param appliedRate     적용이율 = min(원채권 연체이율, 20%)
 * @param interestCharged 양수 후 이자 "부과"(true) / "중단"(false)
 */
public record Acquisition(
		LocalDate acquiredOn,
		Money acquisitionPrice,
		Money preAcqInterest,
		Money cost,
		InterestRate appliedRate,
		boolean interestCharged) {

	/** 원채권 연체이율이 없으면 약정이율 + 3%p, 적용이율은 20% 상한 (R17) */
	public static InterestRate appliedRate(InterestRate originalRate, InterestRate originalLateRate) {
		InterestRate late = originalLateRate != null ? originalLateRate : originalRate.plus(InterestRate.of("3"));
		return late.min(InterestRate.LEGAL_MAX);
	}
}
