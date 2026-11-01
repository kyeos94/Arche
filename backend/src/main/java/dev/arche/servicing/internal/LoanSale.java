package dev.arche.servicing.internal;

import dev.arche.common.Money;
import dev.arche.common.jpa.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * 매각·환매 기록 (R40·R41). 잔액 효과는 SALE·REPURCHASE 재생 이벤트가 만들고, 이 행은 양수인·매각가·양도 통지 이력을 남긴다.
 */
@Entity
@Table(name = "loan_sale")
class LoanSale extends BaseEntity {

	enum Status {
		ACTIVE, REPURCHASED, CANCELLED
	}

	/** 환매 가능 기간 (정책서 5장, R41) */
	static final int REPURCHASE_MONTHS = 6;

	private long accountId;
	private long saleEventId;
	private String buyer;
	private long salePrice;
	private LocalDate soldOn;
	private LocalDate noticeSentOn;
	private String reason;
	@Enumerated(EnumType.STRING)
	private Status status;
	private Long repurchaseEventId;
	private LocalDate repurchasedOn;
	private Long repurchasePrice;
	private String repurchaseReason;

	protected LoanSale() {
	}

	LoanSale(long accountId, long saleEventId, String buyer, Money salePrice, LocalDate soldOn,
			LocalDate noticeSentOn, String reason) {
		this.accountId = accountId;
		this.saleEventId = saleEventId;
		this.buyer = buyer;
		this.salePrice = salePrice.won();
		this.soldOn = soldOn;
		this.noticeSentOn = noticeSentOn;
		this.reason = reason;
		this.status = Status.ACTIVE;
	}

	/** 환매 마감일: 매각일 + 6개월 (같은 날짜, 없으면 말일) */
	LocalDate repurchaseDeadline() {
		return soldOn.plusMonths(REPURCHASE_MONTHS);
	}

	/** 환매가 = 매각가 (정책서 5장) */
	void repurchased(long eventId, LocalDate on, String why) {
		this.status = Status.REPURCHASED;
		this.repurchaseEventId = eventId;
		this.repurchasedOn = on;
		this.repurchasePrice = salePrice;
		this.repurchaseReason = why;
	}

	void cancelled() {
		this.status = Status.CANCELLED;
	}

	long accountId() {
		return accountId;
	}

	long saleEventId() {
		return saleEventId;
	}

	String buyer() {
		return buyer;
	}

	long salePrice() {
		return salePrice;
	}

	LocalDate soldOn() {
		return soldOn;
	}

	LocalDate noticeSentOn() {
		return noticeSentOn;
	}

	String reason() {
		return reason;
	}

	Status status() {
		return status;
	}

	Long repurchaseEventId() {
		return repurchaseEventId;
	}

	LocalDate repurchasedOn() {
		return repurchasedOn;
	}

	Long repurchasePrice() {
		return repurchasePrice;
	}

	String repurchaseReason() {
		return repurchaseReason;
	}
}
