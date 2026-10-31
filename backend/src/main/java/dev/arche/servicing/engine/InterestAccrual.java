package dev.arche.servicing.engine;

import dev.arche.common.DateRange;
import dev.arche.common.InterestRate;
import dev.arche.common.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 구간 분할 일할 이자 (정책서 2장, R2·R12).
 * 기준금액과 이율이 같은 연속된 날을 한 구간으로 묶고, 구간마다 원 미만 절사 후 합산한다.
 */
public final class InterestAccrual {

	/** 하루의 이자 기준: 기준금액 × 이율 */
	public record Basis(Money base, InterestRate rate) {
	}

	public record Segment(DateRange period, Money base, InterestRate rate, Money interest) {
	}

	public record Result(Money total, List<Segment> segments) {
		static final Result NONE = new Result(Money.ZERO, List.of());
	}

	private InterestAccrual() {
	}

	public static Result accrue(DateRange period, Function<LocalDate, Basis> basisAt) {
		if (period.isEmpty()) {
			return Result.NONE;
		}
		List<Segment> segments = new ArrayList<>();
		Money total = Money.ZERO;
		LocalDate segStart = period.from();
		Basis current = basisAt.apply(segStart);
		for (LocalDate d = segStart.plusDays(1); !d.isAfter(period.to()); d = d.plusDays(1)) {
			Basis next = d.isBefore(period.to()) ? basisAt.apply(d) : null;
			if (next == null || !sameBasis(current, next)) {
				DateRange range = DateRange.of(segStart, d);
				Money interest = current.rate().interest(current.base(), range.days());
				if (!current.base().isZero() && !current.rate().isZero()) {
					segments.add(new Segment(range, current.base(), current.rate(), interest));
				}
				total = total.plus(interest);
				segStart = d;
				current = next;
			}
		}
		return new Result(total, List.copyOf(segments));
	}

	/** 기준금액·이율이 일정한 단일 구간 */
	public static Money simple(Money base, InterestRate rate, DateRange period) {
		return rate.interest(base, period.days());
	}

	private static boolean sameBasis(Basis a, Basis b) {
		return a.base().equals(b.base()) && Objects.equals(a.rate(), b.rate());
	}
}
