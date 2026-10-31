package dev.arche.servicing.internal;

import dev.arche.common.InterestRate;
import dev.arche.common.Money;
import dev.arche.common.jpa.BaseEntity;
import dev.arche.servicing.engine.Acquisition;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 매입채권 양수 정보 (정책서 1-1장 양수 등록) */
@Entity
@Table(name = "acquisition")
class AcquisitionRow extends BaseEntity {

	private long accountId;
	private LocalDate acquiredOn;
	private long acquisitionPrice;
	private String seller;
	private LocalDate originalContractDate;
	private BigDecimal originalRate;
	private BigDecimal originalLateRate;
	private BigDecimal appliedRate;
	private long acquiredPrincipal;
	private long preAcqInterest;
	private long acquiredCost;
	private String interestMode;
	private LocalDate originalAccelerationDate;
	private LocalDate limitationStartDate;
	private LocalDate judgmentConfirmedOn;

	protected AcquisitionRow() {
	}

	AcquisitionRow(long accountId, LocalDate acquiredOn, Money price, String seller, LocalDate originalContractDate,
			InterestRate originalRate, InterestRate originalLateRate, Money principal, Money preAcqInterest,
			Money cost, boolean interestCharged, LocalDate originalAccelerationDate, LocalDate limitationStartDate,
			LocalDate judgmentConfirmedOn) {
		this.accountId = accountId;
		this.acquiredOn = acquiredOn;
		this.acquisitionPrice = price.won();
		this.seller = seller;
		this.originalContractDate = originalContractDate;
		this.originalRate = originalRate.percent();
		this.originalLateRate = originalLateRate == null ? null : originalLateRate.percent();
		this.appliedRate = Acquisition.appliedRate(originalRate, originalLateRate).percent();
		this.acquiredPrincipal = principal.won();
		this.preAcqInterest = preAcqInterest.won();
		this.acquiredCost = cost.won();
		this.interestMode = interestCharged ? "CHARGE" : "STOP";
		this.originalAccelerationDate = originalAccelerationDate;
		this.limitationStartDate = limitationStartDate;
		this.judgmentConfirmedOn = judgmentConfirmedOn;
	}

	Acquisition toEngine() {
		return new Acquisition(acquiredOn, Money.won(acquisitionPrice), Money.won(preAcqInterest),
				Money.won(acquiredCost), new InterestRate(appliedRate), "CHARGE".equals(interestMode));
	}

	Money acquisitionPrice() {
		return Money.won(acquisitionPrice);
	}

	String seller() {
		return seller;
	}

	InterestRate appliedRate() {
		return new InterestRate(appliedRate);
	}

	String interestMode() {
		return interestMode;
	}

	LocalDate limitationStartDate() {
		return limitationStartDate;
	}

	LocalDate judgmentConfirmedOn() {
		return judgmentConfirmedOn;
	}

	LocalDate acquiredOn() {
		return acquiredOn;
	}
}
