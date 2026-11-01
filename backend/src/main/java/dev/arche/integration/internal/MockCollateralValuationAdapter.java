package dev.arche.integration.internal;

import dev.arche.common.Money;
import dev.arche.integration.CollateralValuationPort;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Mock 담보 시세: 고정 시세표 (guide 11.1). 숫자는 구현 편의를 위한 가상 값이며 실제 시세가 아니다.
 * <ul>
 * <li>차량: 차종 구분별 기준가 × 잔존율. 잔존율 = 1 − 연식 경과 연수 × 10%, 최저 20%</li>
 * <li>부동산: 소재지에 들어 있는 지역 이름별 ㎡당 단가 × 전용면적 (먼저 맞는 지역, 없으면 기본 단가)</li>
 * </ul>
 */
@Component
class MockCollateralValuationAdapter implements CollateralValuationPort {

	static final String SOURCE = "가상 시세표";

	static final Map<String, Long> VEHICLE_BASE = new LinkedHashMap<>();
	static final Map<String, Long> REGION_PER_M2 = new LinkedHashMap<>();
	static final long DEFAULT_PER_M2 = 3_000_000;
	static final BigDecimal YEARLY_DEPRECIATION = new BigDecimal("0.10");
	static final BigDecimal MIN_RESIDUAL = new BigDecimal("0.20");

	static {
		VEHICLE_BASE.put("경차", 15_000_000L);
		VEHICLE_BASE.put("소형", 22_000_000L);
		VEHICLE_BASE.put("중형", 32_000_000L);
		VEHICLE_BASE.put("대형", 48_000_000L);
		VEHICLE_BASE.put("SUV", 40_000_000L);
		VEHICLE_BASE.put("승합", 35_000_000L);
		VEHICLE_BASE.put("화물", 30_000_000L);
		REGION_PER_M2.put("가상시 중앙구", 9_000_000L);
		REGION_PER_M2.put("가상시", 6_000_000L);
		REGION_PER_M2.put("예시군", 2_000_000L);
	}

	private final CallLogger calls;

	MockCollateralValuationAdapter(CallLogger calls) {
		this.calls = calls;
	}

	@Override
	public Optional<Valuation> vehicle(String vehicleClass, int modelYear, LocalDate asOf) {
		return calls.call(SOURCE, "차량 시세", vehicleClass + " " + modelYear + "년식",
				() -> vehiclePrice(vehicleClass, modelYear, asOf),
				v -> new CallLogger.Outcome(v.isPresent() ? "OK" : "NOT_FOUND", v.isPresent()));
	}

	static Optional<Valuation> vehiclePrice(String vehicleClass, int modelYear, LocalDate asOf) {
		Long base = VEHICLE_BASE.get(vehicleClass);
		if (base == null || modelYear > asOf.getYear() || modelYear < 1980) {
			return Optional.empty();
		}
		int age = asOf.getYear() - modelYear;
		BigDecimal residual = BigDecimal.ONE.subtract(YEARLY_DEPRECIATION.multiply(BigDecimal.valueOf(age)))
				.max(MIN_RESIDUAL);
		Money price = Money.floor(BigDecimal.valueOf(base).multiply(residual));
		return Optional.of(new Valuation(price, asOf, SOURCE,
				vehicleClass + " 기준가 " + Money.won(base) + " × 잔존율 " + residual.stripTrailingZeros().toPlainString()
						+ " (" + age + "년 경과)"));
	}

	@Override
	public Optional<Valuation> realEstate(String address, BigDecimal areaM2, LocalDate asOf) {
		return calls.call(SOURCE, "부동산 시세", "면적 " + areaM2 + "㎡", () -> realEstatePrice(address, areaM2, asOf),
				v -> new CallLogger.Outcome(v.isPresent() ? "OK" : "NOT_FOUND", v.isPresent()));
	}

	static Optional<Valuation> realEstatePrice(String address, BigDecimal areaM2, LocalDate asOf) {
		if (address == null || areaM2 == null || areaM2.signum() <= 0) {
			return Optional.empty();
		}
		String region = REGION_PER_M2.keySet().stream().filter(address::contains).findFirst().orElse(null);
		long perM2 = region == null ? DEFAULT_PER_M2 : REGION_PER_M2.get(region);
		Money price = Money.floor(BigDecimal.valueOf(perM2).multiply(areaM2));
		return Optional.of(new Valuation(price, asOf, SOURCE, (region == null ? "기본" : region) + " ㎡당 "
				+ Money.won(perM2) + " × " + areaM2.stripTrailingZeros().toPlainString() + "㎡"));
	}
}
