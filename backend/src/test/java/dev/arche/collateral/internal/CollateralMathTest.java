package dev.arche.collateral.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arche.common.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 담보 인정가액·LTV (정책서 1장, R60) */
class CollateralMathTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);

	@Test
	void 차량은_시세의_70퍼센트_부동산은_60퍼센트에서_선순위를_뺀다() {
		assertThat(CollateralMath.recognized(CollateralType.VEHICLE, Money.won(22_400_001), Money.ZERO))
				.isEqualTo(Money.won(15_680_000)); // 15,680,000.7 → 절사
		assertThat(CollateralMath.recognized(CollateralType.REAL_ESTATE, Money.won(500_000_000), Money.won(120_000_000)))
				.isEqualTo(Money.won(180_000_000));
		// 선순위가 인정액보다 크면 0
		assertThat(CollateralMath.recognized(CollateralType.REAL_ESTATE, Money.won(100_000_000), Money.won(70_000_000)))
				.isEqualTo(Money.ZERO);
	}

	@Test
	void LTV는_잔액과_선순위를_시세_합계로_나눠_소수_첫째_자리까지() {
		var summary = CollateralMath.summarize(List.of(
				new CollateralMath.Line(CollateralType.REAL_ESTATE, Money.won(500_000_000), TODAY.minusDays(10),
						Money.won(120_000_000)),
				new CollateralMath.Line(CollateralType.VEHICLE, Money.won(30_000_000), TODAY.minusDays(91), Money.ZERO),
				new CollateralMath.Line(CollateralType.VEHICLE, null, null, Money.ZERO)), Money.won(150_000_000), TODAY);
		assertThat(summary.totalPrice()).isEqualTo(Money.won(530_000_000));
		assertThat(summary.totalRecognized()).isEqualTo(Money.won(201_000_000));
		// (150,000,000 + 120,000,000) / 530,000,000 = 50.94…% → 50.9
		assertThat(summary.ltv()).isEqualByComparingTo(new BigDecimal("50.9"));
		assertThat(summary.margin()).isEqualTo(Money.won(51_000_000));
		assertThat(summary.valuedCount()).isEqualTo(2);
		assertThat(summary.staleCount()).isEqualTo(1);
	}

	@Test
	void 시세가_없으면_LTV를_내지_않는다() {
		var summary = CollateralMath.summarize(List.of(), Money.won(1_000_000), TODAY);
		assertThat(summary.ltv()).isNull();
		assertThat(summary.margin()).isEqualTo(Money.won(-1_000_000));
	}
}
