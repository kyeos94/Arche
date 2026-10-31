package dev.arche.product.internal;

import dev.arche.common.BusinessException;
import dev.arche.product.ProductCatalog;
import dev.arche.product.ProductView;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

interface LoanProductRepository extends JpaRepository<LoanProduct, Long> {

	Optional<LoanProduct> findFirstByCodeOrderByProductVersionDesc(String code);
}

@Service
@Transactional(readOnly = true)
class JpaProductCatalog implements ProductCatalog {

	private final LoanProductRepository products;

	JpaProductCatalog(LoanProductRepository products) {
		this.products = products;
	}

	@Override
	@Cacheable(value = "product-by-code", key = "#code")
	public ProductView current(String code) {
		return products.findFirstByCodeOrderByProductVersionDesc(code).map(LoanProduct::view)
				.orElseThrow(() -> new BusinessException("PRODUCT_NOT_FOUND", "상품이 없습니다: " + code));
	}

	@Override
	@Cacheable(value = "product-by-id", key = "#id")
	public ProductView byId(long id) {
		return products.findById(id).map(LoanProduct::view)
				.orElseThrow(() -> new BusinessException("PRODUCT_NOT_FOUND", "상품이 없습니다: " + id));
	}

	@Override
	public List<ProductView> all() {
		return products.findAll().stream().map(LoanProduct::view)
				.sorted(Comparator.comparing(ProductView::code).thenComparing(ProductView::productVersion)).toList();
	}
}
