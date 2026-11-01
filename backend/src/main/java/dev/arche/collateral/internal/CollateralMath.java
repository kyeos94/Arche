package dev.arche.collateral.internal;

import dev.arche.common.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 담보 인정가액과 LTV (정책서 1장, R60).
 * <ul>
 * <li>인정가액 = 시세 × 인정비율(차량 70%, 부동산 60%) 원 미만 절사 − 선순위 채권액, 0 미만이면 0</li>
 * <li>LTV(%) = (대출 원금 잔액 + 선순위 채권액 합계) ÷ 시세 합계, 소수 둘째 자리에서 반올림 (R25와 같은 표시)</li>
 * <li>담보 여유 = 인정가액 합계 − 대출 원금 잔액 (음수면 부족)</li>
 * <li>시세 조회 후 90일이 지나면 "재조회 필요"로 표시. 시세가 없는 담보는 합계에서 뺀다</li>
 * </ul>
 */
final class CollateralMath {

	static final BigDecimal VEHICLE_RATIO = new BigDecimal("0.70");
	static final BigDecimal REAL_ESTATE_RATIO = new BigDecimal("0.60");
	static final int STALE_DAYS = 90;

	record Line(CollateralType type, @Nullable Money price, @Nullable LocalDate valuedOn, Money seniorClaim) {
	}

	record Summary(Money totalPrice, Money totalSenior, Money totalRecognized, Money principalBalance,
			@Nullable BigDecimal ltv, Money margin, int valuedCount, int staleCount) {
	}

	private CollateralMath() {
	}

	static BigDecimal ratio(CollateralType type) {
		return type == CollateralType.VEHICLE ? VEHICLE_RATIO : REAL_ESTATE_RATIO;
	}

	static Money recognized(CollateralType type, Money price, Money seniorClaim) {
		return Money.floor(price.toBigDecimal().multiply(ratio(type))).minus(seniorClaim).max(Money.ZERO);
	}

	static boolean stale(@Nullable LocalDate valuedOn, LocalDate today) {
		return valuedOn == null || ChronoUnit.DAYS.between(valuedOn, today) > STALE_DAYS;
	}

	static Summary summarize(List<Line> lines, Money principalBalance, LocalDate today) {
		Money price = Money.ZERO;
		Money senior = Money.ZERO;
		Money recognized = Money.ZERO;
		int valued = 0;
		int stale = 0;
		for (Line l : lines) {
			if (l.price() == null) {
				continue;
			}
			valued++;
			if (stale(l.valuedOn(), today)) {
				stale++;
			}
			price = price.plus(l.price());
			senior = senior.plus(l.seniorClaim());
			recognized = recognized.plus(recognized(l.type(), l.price(), l.seniorClaim()));
		}
		BigDecimal ltv = price.isZero() ? null : principalBalance.plus(senior).percentOf(price);
		return new Summary(price, senior, recognized, principalBalance, ltv, recognized.minus(principalBalance), valued,
				stale);
	}
}
