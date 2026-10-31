package dev.arche.product;

import java.util.List;

/**
 * 상품 조회 (정책서 1장). 상품 설정을 바꾸면 새 버전이 생기고 기존 계약은 실행 당시 버전을 유지한다.
 */
public interface ProductCatalog {

	/** 코드의 현재(최신) 버전 */
	ProductView current(String code);

	ProductView byId(long id);

	List<ProductView> all();
}
