package dev.arche.servicing.internal;

import dev.arche.common.InterestRate;
import dev.arche.common.Money;
import dev.arche.common.RepaymentMethod;
import dev.arche.common.jpa.BaseEntity;
import dev.arche.servicing.engine.AccountStatus;
import dev.arche.servicing.engine.SimulationResult;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 계약. 금리·약정일의 현재값 컬럼은 두지 않는다 (RateTerm·DueDayTerm 이력에서 조회, CLAUDE.md).
 * 잔액·미납 이자·연체 등 요약 컬럼은 엔진 재생 결과의 스냅샷이며 일마감에서 원장과 대사한다.
 */
@Entity
@Table(name = "loan_account")
class LoanAccount extends BaseEntity {

	enum Origin {
		ORIGINATED, ACQUIRED
	}

	private String accountNo;
	private long customerId;
	private long productId;
	private String productCode;
	private int productVersion;
	private Long applicationId;
	@Enumerated(EnumType.STRING)
	private Origin origin;
	@Enumerated(EnumType.STRING)
	private RepaymentMethod repaymentMethod;
	private long initialPrincipal;
	private LocalDate disbursedOn;
	private LocalDate maturityDate;
	private int termMonths;
	private int graceMonths;
	private BigDecimal prepaymentFeeRate;
	private Long installmentAmount;
	private String status;
	private LocalDate acceleratedOn;
	private LocalDate paidOffOn;
	private LocalDate lastSettledOn;
	private long principalBalance;
	private long suspenseBalance;
	private long costBalance;
	private long preAcqInterest;
	private long unpaidNormalInterest;
	private long unpaidLateInterest;
	private long accruedNormalInterest;
	private long accruedLateInterest;
	private long refundPending;
	private long totalPaid;
	private long payoffAmount;
	private LocalDate delinquentSince;
	private int delinquencyDays;
	private LocalDate nextDueDate;
	private LocalDate asOf;
	private Long assignedStaffId;
	private String virtualAccountNo;
	private int scheduleVersion;
	private LocalDate chargedOffOn;
	private LocalDate soldOn;
	private long transferPending;

	protected LoanAccount() {
	}

	LoanAccount(String accountNo, long customerId, long productId, String productCode, int productVersion,
			Long applicationId, Origin origin, RepaymentMethod method, Money principal, LocalDate disbursedOn,
			int termMonths, int graceMonths, InterestRate prepaymentFeeRate, Long assignedStaffId,
			String virtualAccountNo) {
		this.accountNo = accountNo;
		this.customerId = customerId;
		this.productId = productId;
		this.productCode = productCode;
		this.productVersion = productVersion;
		this.applicationId = applicationId;
		this.origin = origin;
		this.repaymentMethod = method;
		this.initialPrincipal = principal.won();
		this.disbursedOn = disbursedOn;
		this.termMonths = termMonths;
		this.graceMonths = graceMonths;
		this.prepaymentFeeRate = prepaymentFeeRate.percent();
		this.assignedStaffId = assignedStaffId;
		this.virtualAccountNo = virtualAccountNo;
		this.status = AccountStatus.NORMAL.name();
		this.lastSettledOn = disbursedOn;
		this.principalBalance = principal.won();
		this.asOf = disbursedOn;
		this.scheduleVersion = 1;
	}

	/** 엔진 재생 결과를 요약 컬럼에 반영 */
	void apply(SimulationResult r) {
		status = r.status().name();
		acceleratedOn = r.acceleratedOn();
		paidOffOn = r.paidOffOn();
		lastSettledOn = r.lastSettledOn();
		maturityDate = r.maturityDate();
		installmentAmount = r.installmentAmount() == null ? null : r.installmentAmount().won();
		principalBalance = r.principalBalance().won();
		suspenseBalance = r.suspense().won();
		costBalance = r.cost().won();
		preAcqInterest = r.preAcqInterest().won();
		unpaidNormalInterest = r.unpaidNormalInterest().won();
		unpaidLateInterest = r.unpaidLateInterest().won();
		accruedNormalInterest = r.accruedNormalInterest().won();
		accruedLateInterest = r.accruedLateInterest().won();
		refundPending = r.refundPending().won();
		totalPaid = r.totalPaid().won();
		payoffAmount = r.payoffAmount().won();
		delinquentSince = r.delinquentSince();
		delinquencyDays = (int) r.delinquencyDays();
		nextDueDate = r.nextInstallment().map(i -> i.dueDate()).orElse(null);
		asOf = r.asOf();
		scheduleVersion = r.scheduleVersion();
		chargedOffOn = r.chargedOffOn();
		soldOn = r.soldOn();
		transferPending = r.transferPending().won();
	}

	String accountNo() {
		return accountNo;
	}

	long customerId() {
		return customerId;
	}

	long productId() {
		return productId;
	}

	String productCode() {
		return productCode;
	}

	int productVersion() {
		return productVersion;
	}

	Long applicationId() {
		return applicationId;
	}

	Origin origin() {
		return origin;
	}

	RepaymentMethod repaymentMethod() {
		return repaymentMethod;
	}

	Money initialPrincipal() {
		return Money.won(initialPrincipal);
	}

	LocalDate disbursedOn() {
		return disbursedOn;
	}

	LocalDate maturityDate() {
		return maturityDate;
	}

	int termMonths() {
		return termMonths;
	}

	int graceMonths() {
		return graceMonths;
	}

	InterestRate prepaymentFeeRate() {
		return new InterestRate(prepaymentFeeRate);
	}

	Long installmentAmount() {
		return installmentAmount;
	}

	AccountStatus status() {
		return AccountStatus.valueOf(status);
	}

	LocalDate acceleratedOn() {
		return acceleratedOn;
	}

	LocalDate paidOffOn() {
		return paidOffOn;
	}

	LocalDate lastSettledOn() {
		return lastSettledOn;
	}

	long principalBalance() {
		return principalBalance;
	}

	long suspenseBalance() {
		return suspenseBalance;
	}

	long costBalance() {
		return costBalance;
	}

	long preAcqInterest() {
		return preAcqInterest;
	}

	long unpaidNormalInterest() {
		return unpaidNormalInterest;
	}

	long unpaidLateInterest() {
		return unpaidLateInterest;
	}

	long accruedNormalInterest() {
		return accruedNormalInterest;
	}

	long accruedLateInterest() {
		return accruedLateInterest;
	}

	long refundPending() {
		return refundPending;
	}

	long totalPaid() {
		return totalPaid;
	}

	long payoffAmount() {
		return payoffAmount;
	}

	LocalDate delinquentSince() {
		return delinquentSince;
	}

	int delinquencyDays() {
		return delinquencyDays;
	}

	LocalDate nextDueDate() {
		return nextDueDate;
	}

	LocalDate asOf() {
		return asOf;
	}

	Long assignedStaffId() {
		return assignedStaffId;
	}

	String virtualAccountNo() {
		return virtualAccountNo;
	}

	int scheduleVersion() {
		return scheduleVersion;
	}

	/** 상각 확정일 (R39) */
	LocalDate chargedOffOn() {
		return chargedOffOn;
	}

	/** 매각 중이면 매각일 (R40) */
	LocalDate soldOn() {
		return soldOn;
	}

	/** 양수인 이전 대상 잔액 (R40) */
	long transferPending() {
		return transferPending;
	}
}
