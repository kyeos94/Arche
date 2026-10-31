package dev.arche.collection;

import dev.arche.common.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * 매입채권 분할납부 약속 이행 판정 (정책서 1-1장, R18, G19).
 * 약속일 다음 날까지 약속액의 90% 이상 입금이면 이행. 판정 대상 입금 = 직전 약속 기한 다음 날(첫 약속은 등록일)부터
 * 이번 기한까지의 입금 합계. 약속일이 없는 날짜면 말일, 휴일 유예 없음. 3회 연속 불이행이면 약속 해제.
 */
public final class PromiseTracker {

	public static final BigDecimal FULFILL_RATIO = new BigDecimal("0.9");
	public static final int MAX_CONSECUTIVE_MISSES = 3;

	public enum Action {
		NONE, NOTIFY_STAFF, TERMINATE
	}

	public record Deposit(LocalDate date, Money amount) {
	}

	public record Judgment(LocalDate promiseDate, Money paid, boolean fulfilled, int consecutiveMisses,
			Action action) {
	}

	private PromiseTracker() {
	}

	/**
	 * @param registeredOn 약속 등록일
	 * @param firstMonth   첫 약속 월
	 * @param day          매월 약속일
	 * @param amount       약속액
	 * @param count        판정할 약속 수
	 */
	public static List<Judgment> judge(LocalDate registeredOn, YearMonth firstMonth, int day, Money amount,
			int count, List<Deposit> deposits) {
		Money threshold = Money.floor(amount.toBigDecimal().multiply(FULFILL_RATIO).setScale(0, RoundingMode.UP));
		List<Judgment> result = new ArrayList<>();
		LocalDate from = registeredOn;
		int misses = 0;
		for (int i = 0; i < count && misses < MAX_CONSECUTIVE_MISSES; i++) {
			YearMonth m = firstMonth.plusMonths(i);
			LocalDate promise = m.atDay(Math.min(day, m.lengthOfMonth()));
			LocalDate deadline = promise.plusDays(1);
			LocalDate start = from;
			Money paid = deposits.stream().filter(d -> !d.date().isBefore(start) && !d.date().isAfter(deadline))
					.map(Deposit::amount).reduce(Money.ZERO, Money::plus);
			boolean fulfilled = !paid.isLessThan(threshold);
			misses = fulfilled ? 0 : misses + 1;
			Action action = fulfilled ? Action.NONE
					: misses >= MAX_CONSECUTIVE_MISSES ? Action.TERMINATE : Action.NOTIFY_STAFF;
			result.add(new Judgment(promise, paid, fulfilled, misses, action));
			from = deadline.plusDays(1);
		}
		return result;
	}
}
