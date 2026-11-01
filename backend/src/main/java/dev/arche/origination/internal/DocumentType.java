package dev.arche.origination.internal;

/** 징구 서류 종류 (R54). 일반 명칭만 쓰고 특정 기관의 서식 이름·코드는 쓰지 않는다 */
enum DocumentType {
	ID_CARD("신분증 사본"),
	INCOME_PROOF("소득 증빙"),
	EMPLOYMENT_PROOF("재직 증빙"),
	VEHICLE_REGISTRATION("차량 등록 서류"),
	PROPERTY_REGISTRY("부동산 권리 서류");

	final String label;

	DocumentType(String label) {
		this.label = label;
	}
}
