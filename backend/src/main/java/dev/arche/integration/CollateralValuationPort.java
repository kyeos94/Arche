package dev.arche.integration;

import dev.arche.common.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 담보 시세 포트 (guide 11.1 CollateralValuationPort): 차량·부동산 시세, 동기 조회. Mock은 고정 시세표.
 * 실제 시세 기관·코드 체계는 담지 않는다.
 */
public interface CollateralValuationPort {

	/**
	 * @param source 시세 출처 이름 (Mock은 "가상 시세표")
	 * @param basis  계산 근거 설명 (화면 표시용)
	 */
	record Valuation(Money price, LocalDate asOf, String source, String basis) {
	}

	/** 차량: 차종 구분 + 연식. 시세표에 없으면 빈 값 */
	Optional<Valuation> vehicle(String vehicleClass, int modelYear, LocalDate asOf);

	/** 부동산: 소재지 + 전용면적(㎡). 시세표에 없으면 빈 값 */
	Optional<Valuation> realEstate(String address, BigDecimal areaM2, LocalDate asOf);
}
