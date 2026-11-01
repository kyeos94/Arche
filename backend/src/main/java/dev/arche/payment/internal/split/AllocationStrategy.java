package dev.arche.payment.internal.split;

import dev.arche.common.BusinessException;
import dev.arche.common.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * 한 고객의 여러 계약에 입금 1건을 나누는 배분 전략 (정책서 R47, mvp.md 5장 "payment 모듈의 배분 전략 인터페이스").
 * 순수 함수다. 나눈 몫은 계약마다 일반 입금 흐름(R13 기산일, 4장 충당)으로 처리되므로 여기서는 금액만 정한다.
 */
public interface AllocationStrategy {

	enum Method {
		/** 연체 시작일이 오래된 계약부터 (기본) */
		OLDEST_DELINQUENCY_FIRST,
		/** 완제 금액 비율 */
		PROPORTIONAL,
		/** 계약별 금액 직접 지정 */
		MANUAL
	}

	/**
	 * 배분 대상 계약의 배분 시점 금액 (업무일자로 재생한 값).
	 *
	 * @param overdue 지금 갚아야 할 연체금 (기한이익상실·만기 경과·매입채권은 완제 금액)
	 * @param nextDue 다음 약정일 예정액 (아직 도래 전)
	 * @param payoff  오늘 완제 금액 (R8)
	 */
	record Target(long accountId, String accountNo, @Nullable LocalDate delinquentSince,
			@Nullable LocalDate nextDueDate, Money overdue, Money nextDue, Money payoff) {
	}

	record Part(long accountId, Money amount) {
	}

	Method method();

	/**
	 * @param manual MANUAL에서만 쓰는 계약별 지정 금액
	 * @return 0원이 아닌 몫만, 계약 id 오름차순 (계약 락 순서, R48)
	 */
	List<Part> split(Money total, List<Target> targets, Map<Long, Money> manual);

	static AllocationStrategy of(Method method) {
		return Registry.ALL.get(method);
	}

	// ------------------------------------------------------------------ 구현

	/** R47 ①: 연체금 → 다음 회차 예정액 → 완제 금액 → 남으면 첫 계약 (과입금 반환 대기) */
	final class OldestDelinquencyFirst implements AllocationStrategy {

		static final Comparator<Target> DELINQUENCY_ORDER = Comparator
				.comparing(Target::delinquentSince, Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparing(Target::nextDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparingLong(Target::accountId);

		static final Comparator<Target> NEXT_DUE_ORDER = Comparator
				.comparing(Target::nextDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparingLong(Target::accountId);

		@Override
		public Method method() {
			return Method.OLDEST_DELINQUENCY_FIRST;
		}

		@Override
		public List<Part> split(Money total, List<Target> targets, Map<Long, Money> manual) {
			requireTargets(targets);
			List<Target> byDelinquency = targets.stream().sorted(DELINQUENCY_ORDER).toList();
			List<Target> byNextDue = targets.stream().sorted(NEXT_DUE_ORDER).toList();
			Map<Long, Money> alloc = new LinkedHashMap<>();
			Money remaining = total;
			// 1) 연체금 (완제 금액 한도)
			remaining = fill(remaining, byDelinquency, alloc, t -> t.overdue().min(t.payoff()));
			// 2) 다음 회차 예정액 (연체금 + 예정액, 완제 금액 한도)
			remaining = fill(remaining, byNextDue, alloc, t -> t.overdue().plus(t.nextDue()).min(t.payoff()));
			// 3) 완제 금액까지
			remaining = fill(remaining, byDelinquency, alloc, Target::payoff);
			// 4) 모든 계약을 완제하고도 남으면 첫 계약에 (그 계약에서 과입금 반환 대기, 정책서 4장)
			if (remaining.isPositive()) {
				long first = byDelinquency.getFirst().accountId();
				alloc.merge(first, remaining, Money::plus);
			}
			return parts(alloc);
		}

		private static Money fill(Money remaining, List<Target> order, Map<Long, Money> alloc,
				java.util.function.Function<Target, Money> cap) {
			for (Target t : order) {
				if (!remaining.isPositive()) {
					break;
				}
				Money already = alloc.getOrDefault(t.accountId(), Money.ZERO);
				Money room = cap.apply(t).minus(already);
				if (room.isPositive()) {
					Money take = room.min(remaining);
					alloc.put(t.accountId(), already.plus(take));
					remaining = remaining.minus(take);
				}
			}
			return remaining;
		}
	}

	/** R47 ②: 완제 금액 비율로 나누고 원 미만 절사, 절사 차액은 완제 금액이 가장 큰 계약에 */
	final class Proportional implements AllocationStrategy {

		@Override
		public Method method() {
			return Method.PROPORTIONAL;
		}

		@Override
		public List<Part> split(Money total, List<Target> targets, Map<Long, Money> manual) {
			requireTargets(targets);
			long weight = targets.stream().mapToLong(t -> t.payoff().won()).sum();
			if (weight <= 0) {
				throw new BusinessException("SPLIT_NO_BALANCE", "비례 배분할 잔액이 있는 계약이 없습니다");
			}
			Map<Long, Money> alloc = new LinkedHashMap<>();
			Money assigned = Money.ZERO;
			for (Target t : targets) {
				Money share = total.share(t.payoff().won(), weight);
				alloc.put(t.accountId(), share);
				assigned = assigned.plus(share);
			}
			Money rest = total.minus(assigned);
			if (rest.isPositive()) {
				Target largest = targets.stream()
						.max(Comparator.comparing(Target::payoff)
								.thenComparing(Comparator.comparingLong(Target::accountId).reversed()))
						.orElseThrow();
				alloc.merge(largest.accountId(), rest, Money::plus);
			}
			return parts(alloc);
		}
	}

	/** R47 ③: 계약별 금액 직접 지정, 합계 = 입금액 */
	final class Manual implements AllocationStrategy {

		@Override
		public Method method() {
			return Method.MANUAL;
		}

		@Override
		public List<Part> split(Money total, List<Target> targets, Map<Long, Money> manual) {
			requireTargets(targets);
			if (manual == null || manual.isEmpty()) {
				throw new BusinessException("SPLIT_MANUAL_REQUIRED", "계약별 배분 금액을 입력하세요");
			}
			Money sum = Money.ZERO;
			for (Map.Entry<Long, Money> e : manual.entrySet()) {
				if (targets.stream().noneMatch(t -> t.accountId() == e.getKey())) {
					throw new BusinessException("SPLIT_TARGET_INVALID", "배분 대상이 아닌 계약입니다: " + e.getKey());
				}
				if (e.getValue().isNegative()) {
					throw new BusinessException("AMOUNT_INVALID", "배분 금액은 0 이상이어야 합니다");
				}
				sum = sum.plus(e.getValue());
			}
			if (!sum.equals(total)) {
				throw new BusinessException("SPLIT_SUM_MISMATCH",
						"계약별 배분 합계(" + sum + ")가 입금액(" + total + ")과 다릅니다");
			}
			return parts(manual);
		}
	}

	private static void requireTargets(List<Target> targets) {
		if (targets == null || targets.isEmpty()) {
			throw new BusinessException("SPLIT_NO_TARGET", "배분할 계약이 없습니다");
		}
	}

	private static List<Part> parts(Map<Long, Money> alloc) {
		List<Part> parts = new ArrayList<>();
		new TreeMap<>(alloc).forEach((id, amount) -> {
			if (amount.isPositive()) {
				parts.add(new Part(id, amount));
			}
		});
		return parts;
	}

	final class Registry {

		static final Map<Method, AllocationStrategy> ALL = new EnumMap<>(Method.class);

		static {
			for (AllocationStrategy s : List.of(new OldestDelinquencyFirst(), new Proportional(), new Manual())) {
				ALL.put(s.method(), s);
			}
		}

		private Registry() {
		}
	}
}
