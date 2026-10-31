package dev.arche.product.internal;

import dev.arche.common.InterestRate;
import dev.arche.common.Money;
import dev.arche.common.RepaymentMethod;
import dev.arche.common.jpa.BaseEntity;
import dev.arche.product.ProductView;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "loan_product")
class LoanProduct extends BaseEntity {

	private String code;
	private String name;
	@Enumerated(EnumType.STRING)
	private RepaymentMethod repaymentMethod;
	private BigDecimal defaultRate;
	private long minAmount;
	private long maxAmount;
	private int minTermMonths;
	private int maxTermMonths;
	private int minGraceMonths;
	private int maxGraceMonths;
	private String collateralType;
	private BigDecimal prepaymentFeeRate;
	private int productVersion;
	private LocalDate validFrom;

	protected LoanProduct() {
	}

	ProductView view() {
		return new ProductView(getId(), code, name, repaymentMethod, new InterestRate(defaultRate),
				Money.won(minAmount), Money.won(maxAmount), minTermMonths, maxTermMonths, minGraceMonths,
				maxGraceMonths, collateralType, new InterestRate(prepaymentFeeRate), productVersion);
	}
}
