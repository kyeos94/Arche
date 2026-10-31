package dev.arche.origination.internal;

import dev.arche.common.BusinessException;
import dev.arche.common.jpa.BaseEntity;
import dev.arche.origination.ApplicationStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "loan_application")
class LoanApplication extends BaseEntity {

	private String applicationNo;
	private long customerId;
	private String productCode;
	private long requestedAmount;
	private int termMonths;
	private int graceMonths;
	private int dueDay;
	private Long approvedAmount;
	private BigDecimal approvedRate;
	@Enumerated(EnumType.STRING)
	private ApplicationStatus status;
	private Long underwriterId;
	private LocalDate disbursedOn;

	protected LoanApplication() {
	}

	LoanApplication(String applicationNo, long customerId, String productCode, long requestedAmount, int termMonths,
			int graceMonths, int dueDay) {
		this.applicationNo = applicationNo;
		this.customerId = customerId;
		this.productCode = productCode;
		this.requestedAmount = requestedAmount;
		this.termMonths = termMonths;
		this.graceMonths = graceMonths;
		this.dueDay = dueDay;
		this.status = ApplicationStatus.RECEIVED;
	}

	ApplicationStatus moveTo(ApplicationStatus next) {
		if (!status.canMoveTo(next)) {
			throw new BusinessException("TRANSITION_INVALID", status + " → " + next + " 전이는 허용되지 않습니다");
		}
		ApplicationStatus from = status;
		status = next;
		return from;
	}

	void assign(long staffId) {
		this.underwriterId = staffId;
	}

	void approve(long amount, BigDecimal rate) {
		this.approvedAmount = amount;
		this.approvedRate = rate;
	}

	void disbursed(LocalDate on) {
		this.disbursedOn = on;
	}

	String applicationNo() {
		return applicationNo;
	}

	long customerId() {
		return customerId;
	}

	String productCode() {
		return productCode;
	}

	long requestedAmount() {
		return requestedAmount;
	}

	int termMonths() {
		return termMonths;
	}

	int graceMonths() {
		return graceMonths;
	}

	int dueDay() {
		return dueDay;
	}

	Long approvedAmount() {
		return approvedAmount;
	}

	BigDecimal approvedRate() {
		return approvedRate;
	}

	ApplicationStatus status() {
		return status;
	}

	Long underwriterId() {
		return underwriterId;
	}

	LocalDate disbursedOn() {
		return disbursedOn;
	}
}
