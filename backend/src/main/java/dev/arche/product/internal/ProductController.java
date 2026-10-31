package dev.arche.product.internal;

import dev.arche.product.ProductCatalog;
import dev.arche.product.ProductView;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
class ProductController {

	record ProductDto(long id, String code, String name, String repaymentMethod, BigDecimal defaultRate,
			long minAmount, long maxAmount, int minTermMonths, int maxTermMonths, int minGraceMonths,
			int maxGraceMonths, String collateralType, BigDecimal prepaymentFeeRate, int productVersion) {
	}

	private final ProductCatalog catalog;

	ProductController(ProductCatalog catalog) {
		this.catalog = catalog;
	}

	@GetMapping
	List<ProductDto> list() {
		return catalog.all().stream().map(ProductController::dto).toList();
	}

	private static ProductDto dto(ProductView p) {
		return new ProductDto(p.id(), p.code(), p.name(), p.repaymentMethod().name(), p.defaultRate().percent(),
				p.minAmount().won(), p.maxAmount().won(), p.minTermMonths(), p.maxTermMonths(), p.minGraceMonths(),
				p.maxGraceMonths(), p.collateralType(), p.prepaymentFeeRate().percent(), p.productVersion());
	}
}
