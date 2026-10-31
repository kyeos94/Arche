package dev.arche.servicing.engine;

import dev.arche.common.InterestRate;
import dev.arche.common.Money;
import dev.arche.common.RepaymentMethod;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 계약 조건 (실행 시점 확정값 + 금리 이력).
 *
 * @param termMonths  총 회차 수 (거치 포함). N1은 0
 * @param graceMonths 거치 회차 수 (P4만)
 * @param ratePeriods 금리 이력, 첫 항목의 validFrom = 실행일
 */
public record LoanTerms(
		RepaymentMethod method,
		Money principal,
		LocalDate disbursedOn,
		int termMonths,
		int graceMonths,
		int dueDay,
		List<RatePeriod> ratePeriods,
		InterestRate prepaymentFeeRate,
		@Nullable Acquisition acquisition) {

	public LoanTerms {
		ratePeriods = ratePeriods.stream().sorted(Comparator.comparing(RatePeriod::validFrom)).toList();
		if (method == RepaymentMethod.ACQUIRED && acquisition == null) {
			throw new IllegalArgumentException("매입채권은 양수 조건이 필요합니다");
		}
		if (method != RepaymentMethod.ACQUIRED && ratePeriods.isEmpty()) {
			throw new IllegalArgumentException("금리 이력이 비어 있습니다");
		}
	}

	public static LoanTerms originated(RepaymentMethod method, long principal, LocalDate disbursedOn, int termMonths,
			int graceMonths, int dueDay, String rate, String prepaymentFeeRate) {
		return new LoanTerms(method, Money.won(principal), disbursedOn, termMonths, graceMonths, dueDay,
				List.of(new RatePeriod(disbursedOn, InterestRate.of(rate))), InterestRate.of(prepaymentFeeRate), null);
	}

	public static LoanTerms acquired(Money principal, Acquisition acquisition) {
		return new LoanTerms(RepaymentMethod.ACQUIRED, principal, acquisition.acquiredOn(), 0, 0, 0, List.of(),
				InterestRate.ZERO, acquisition);
	}

	public LoanTerms withRatePeriods(List<RatePeriod> periods) {
		return new LoanTerms(method, principal, disbursedOn, termMonths, graceMonths, dueDay, periods,
				prepaymentFeeRate, acquisition);
	}

	/** 날짜 d의 약정이율 (N1은 적용이율, 이자 중단이면 0) */
	public InterestRate rateAt(LocalDate d) {
		if (acquisition != null) {
			return acquisition.interestCharged() ? acquisition.appliedRate() : InterestRate.ZERO;
		}
		InterestRate rate = ratePeriods.getFirst().rate();
		for (RatePeriod p : ratePeriods) {
			if (!p.validFrom().isAfter(d)) {
				rate = p.rate();
			}
		}
		return rate;
	}

	public InterestRate initialRate() {
		return rateAt(disbursedOn);
	}
}
