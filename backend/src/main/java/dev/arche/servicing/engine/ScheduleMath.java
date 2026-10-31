package dev.arche.servicing.engine;

import dev.arche.common.DateRange;
import dev.arche.common.InterestRate;
import dev.arche.common.Money;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 스케줄 계산식 (정책서 2장, guide 3.2).
 */
public final class ScheduleMath {

	/** 첫 납입일과 실행일 간격이 이보다 짧으면 그다음 달 (정책서 2장) */
	static final int MIN_FIRST_PERIOD_DAYS = 15;

	private ScheduleMath() {
	}

	/** 그 달의 약정일. 그 달에 없는 날이면 말일 */
	public static LocalDate dueDateIn(YearMonth month, int dueDay) {
		return month.atDay(Math.min(dueDay, month.lengthOfMonth()));
	}

	/** 실행일의 다음 달 약정일. 간격이 15일 미만이면 그다음 달 */
	public static LocalDate firstDueDate(LocalDate disbursedOn, int dueDay) {
		LocalDate first = dueDateIn(YearMonth.from(disbursedOn).plusMonths(1), dueDay);
		if (ChronoUnit.DAYS.between(disbursedOn, first) < MIN_FIRST_PERIOD_DAYS) {
			first = dueDateIn(YearMonth.from(disbursedOn).plusMonths(2), dueDay);
		}
		return first;
	}

	public static List<LocalDate> monthlyDueDates(LocalDate first, int dueDay, int count) {
		List<LocalDate> dates = new ArrayList<>(count);
		YearMonth month = YearMonth.from(first);
		for (int i = 0; i < count; i++) {
			dates.add(dueDateIn(month.plusMonths(i), dueDay));
		}
		return dates;
	}

	/** 원리금균등 납입액 A = P·r(1+r)^n / ((1+r)^n − 1), r = 연이율 ÷ 12, 원 미만 절사 */
	public static Money annuity(Money principal, InterestRate annualRate, int n) {
		if (n <= 0) {
			return principal;
		}
		BigDecimal p = principal.toBigDecimal();
		if (annualRate.isZero()) {
			return Money.floor(p.divide(BigDecimal.valueOf(n), MathContext.DECIMAL128));
		}
		BigDecimal r = annualRate.monthlyFraction();
		BigDecimal pow = BigDecimal.ONE.add(r).pow(n, MathContext.DECIMAL128);
		BigDecimal a = p.multiply(r).multiply(pow)
				.divide(pow.subtract(BigDecimal.ONE), MathContext.DECIMAL128);
		return Money.floor(a);
	}

	/** 잔액이 일정한 기간의 이자, 금리 이력 변경일에서만 구간 분할 */
	public static Money periodInterest(Money balance, DateRange period, Function<LocalDate, InterestRate> rateAt) {
		return InterestAccrual.accrue(period, d -> new InterestAccrual.Basis(balance, rateAt.apply(d))).total();
	}

	/** 중도상환수수료 = 상환원금 × 요율 × 잔존일수 ÷ 기준일수 (정책서 4장, R19) */
	public static Money prepaymentFee(Money repaidPrincipal, InterestRate feeRate, LocalDate disbursedOn,
			LocalDate lastDueDate, LocalDate repaidOn) {
		if (feeRate.isZero() || !repaidPrincipal.isPositive()) {
			return Money.ZERO;
		}
		long loanDays = ChronoUnit.DAYS.between(disbursedOn, lastDueDate);
		long baseDays = Math.min(loanDays, 1095);
		long remaining = baseDays - ChronoUnit.DAYS.between(disbursedOn, repaidOn);
		if (remaining <= 0 || baseDays <= 0) {
			return Money.ZERO;
		}
		BigDecimal fee = repaidPrincipal.toBigDecimal().multiply(feeRate.percent())
				.multiply(BigDecimal.valueOf(remaining))
				.divide(BigDecimal.valueOf(100L * baseDays), MathContext.DECIMAL128);
		return Money.floor(fee);
	}
}
