package dev.arche.payment.internal.split;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.arche.common.BusinessException;
import dev.arche.common.Money;
import dev.arche.payment.internal.split.AllocationStrategy.Method;
import dev.arche.payment.internal.split.AllocationStrategy.Part;
import dev.arche.payment.internal.split.AllocationStrategy.Target;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 정책서 R47 일괄 입금 배분 */
class AllocationStrategyTest {

	/** 1번: 연체 07-10부터, 연체금 30만, 완제 500만 */
	static final Target A = new Target(1, "L1", LocalDate.parse("2026-07-10"), LocalDate.parse("2026-11-10"),
			Money.won(300_000), Money.won(150_000), Money.won(5_000_000));
	/** 2번: 연체 09-25부터(더 최근), 연체금 20만, 완제 100만 */
	static final Target B = new Target(2, "L2", LocalDate.parse("2026-09-25"), LocalDate.parse("2026-11-25"),
			Money.won(200_000), Money.won(100_000), Money.won(1_000_000));
	/** 3번: 정상, 다음 약정일 11-05, 예정액 5만, 완제 50만 */
	static final Target C = new Target(3, "L3", null, LocalDate.parse("2026-11-05"), Money.ZERO,
			Money.won(50_000), Money.won(500_000));

	static Map<Long, Long> asMap(List<Part> parts) {
		return parts.stream().collect(java.util.stream.Collectors.toMap(Part::accountId, p -> p.amount().won()));
	}

	@Test
	void 연체_우선_오래된_연체부터_연체금을_채운다() {
		List<Part> parts = AllocationStrategy.of(Method.OLDEST_DELINQUENCY_FIRST).split(Money.won(400_000),
				List.of(C, B, A), Map.of());
		assertThat(asMap(parts)).containsExactlyInAnyOrderEntriesOf(Map.of(1L, 300_000L, 2L, 100_000L));
		assertThat(parts).extracting(Part::accountId).containsExactly(1L, 2L);
	}

	@Test
	void 연체금_다음은_다음_약정일이_빠른_순으로_예정액() {
		// 연체금 50만 → 남은 10만: C(11-05) 5만 → A(11-10) 5만
		List<Part> parts = AllocationStrategy.of(Method.OLDEST_DELINQUENCY_FIRST).split(Money.won(600_000),
				List.of(A, B, C), Map.of());
		assertThat(asMap(parts)).containsExactlyInAnyOrderEntriesOf(Map.of(1L, 350_000L, 2L, 200_000L, 3L, 50_000L));
	}

	@Test
	void 예정액까지_채우고_남으면_연체_순서로_완제_금액까지() {
		// 연체금 50만 + 예정액 30만 = 80만, 남은 20만 → A 완제 한도까지
		List<Part> parts = AllocationStrategy.of(Method.OLDEST_DELINQUENCY_FIRST).split(Money.won(1_000_000),
				List.of(A, B, C), Map.of());
		assertThat(asMap(parts)).containsExactlyInAnyOrderEntriesOf(Map.of(1L, 650_000L, 2L, 300_000L, 3L, 50_000L));
	}

	@Test
	void 모두_완제하고_남는_금액은_첫_계약에() {
		List<Part> parts = AllocationStrategy.of(Method.OLDEST_DELINQUENCY_FIRST).split(Money.won(7_000_000),
				List.of(A, B, C), Map.of());
		assertThat(asMap(parts)).containsExactlyInAnyOrderEntriesOf(Map.of(1L, 5_500_000L, 2L, 1_000_000L, 3L, 500_000L));
	}

	@Test
	void 비례_배분은_완제_금액_비율로_절사하고_차액은_가장_큰_계약에() {
		// 1,000,001원 × 500만/650만 = 769,231.5 → 769,231 / ×100만/650만 = 153,846.3 → 153,846 / ×50만/650만 = 76,923.1 → 76,923
		// 합계 1,000,000 → 차액 1원은 A
		List<Part> parts = AllocationStrategy.of(Method.PROPORTIONAL).split(Money.won(1_000_001), List.of(A, B, C),
				Map.of());
		assertThat(asMap(parts)).containsExactlyInAnyOrderEntriesOf(Map.of(1L, 769_232L, 2L, 153_846L, 3L, 76_923L));
		assertThat(parts.stream().mapToLong(p -> p.amount().won()).sum()).isEqualTo(1_000_001L);
	}

	@Test
	void 수동_지정은_합계가_입금액과_같아야_한다() {
		AllocationStrategy manual = AllocationStrategy.of(Method.MANUAL);
		assertThat(asMap(manual.split(Money.won(300_000), List.of(A, B),
				Map.of(1L, Money.won(100_000), 2L, Money.won(200_000)))))
				.containsExactlyInAnyOrderEntriesOf(Map.of(1L, 100_000L, 2L, 200_000L));
		assertThatThrownBy(() -> manual.split(Money.won(300_000), List.of(A, B), Map.of(1L, Money.won(100_000))))
				.isInstanceOf(BusinessException.class).hasMessageContaining("합계");
		assertThatThrownBy(() -> manual.split(Money.won(100_000), List.of(A), Map.of(9L, Money.won(100_000))))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void 수동_지정의_0원_몫은_빠진다() {
		List<Part> parts = AllocationStrategy.of(Method.MANUAL).split(Money.won(100_000), List.of(A, B),
				Map.of(1L, Money.won(100_000), 2L, Money.ZERO));
		assertThat(parts).extracting(Part::accountId).containsExactly(1L);
	}

	@Test
	void 대상_계약이_없으면_배분하지_않는다() {
		assertThatThrownBy(() -> AllocationStrategy.of(Method.OLDEST_DELINQUENCY_FIRST).split(Money.won(1), List.of(),
				Map.of())).isInstanceOf(BusinessException.class);
	}
}
