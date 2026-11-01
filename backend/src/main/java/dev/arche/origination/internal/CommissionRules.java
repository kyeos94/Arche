package dev.arche.origination.internal;

import dev.arche.common.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 중개수수료 정산·환수 (R56). 순수 함수.
 * <ul>
 * <li>정산: 실행일에 그날 유효한 수수료율로 확정. 수수료 = 실행금액 × 수수료율, 원 미만 절사</li>
 * <li>조기 완제 환수: 실행일부터 3개월 안(완제일 &lt; 실행일 + 3개월)에 완제되면 정산액 전액</li>
 * <li>불완전판매 환수: 실행일부터 1년 안(확인일 &lt; 실행일 + 1년)에 확인되면 정산액 전액</li>
 * </ul>
 */
final class CommissionRules {

	/** 수수료율 입력 상한 (가상 정책값. 실제 법정 상한은 별도 확인) */
	static final BigDecimal MAX_RATE = new BigDecimal("4.00");
	static final int EARLY_PAYOFF_MONTHS = 3;
	static final int MIS_SELLING_YEARS = 1;

	private CommissionRules() {
	}

	static Money commission(Money base, BigDecimal ratePercent) {
		return Money.floor(base.toBigDecimal().multiply(ratePercent).movePointLeft(2));
	}

	/** 그날 유효한 수수료율 [validFrom, validTo) */
	static Optional<BigDecimal> rateOn(List<BrokerCommissionRate> terms, LocalDate day) {
		return terms.stream().filter(t -> t.coversDay(day)).map(BrokerCommissionRate::rate).findFirst();
	}

	static boolean earlyPayoffClawback(LocalDate disbursedOn, LocalDate paidOffOn) {
		return paidOffOn.isBefore(disbursedOn.plusMonths(EARLY_PAYOFF_MONTHS));
	}

	static boolean misSellingClawbackAllowed(LocalDate disbursedOn, LocalDate confirmedOn) {
		return !confirmedOn.isBefore(disbursedOn) && confirmedOn.isBefore(disbursedOn.plusYears(MIS_SELLING_YEARS));
	}

	static void validateRate(BigDecimal rate) {
		if (rate == null || rate.signum() < 0 || rate.compareTo(MAX_RATE) > 0 || rate.scale() > 2) {
			throw new dev.arche.common.BusinessException("BROKER_RATE",
					"수수료율은 0.00 ~ " + MAX_RATE + "% (소수 둘째 자리까지)여야 합니다");
		}
	}
}
