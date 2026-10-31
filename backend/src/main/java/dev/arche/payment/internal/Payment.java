package dev.arche.payment.internal;

import dev.arche.common.Money;
import dev.arche.common.jpa.BaseEntity;
import dev.arche.payment.Payments;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 입금 1건. valueDate는 은행 거래일자이며, 계약의 기산일(R13)은 servicing이 정한다.
 */
@Entity
@Table(name = "payment")
class Payment extends BaseEntity {

	enum Status {
		RECEIVED, ALLOCATED, UNMATCHED, REVERSED, CANCELLED
	}

	private Long accountId;
	@Enumerated(EnumType.STRING)
	private Payments.Channel channel;
	private LocalDate valueDate;
	private LocalDate businessDate;
	private OffsetDateTime receivedAt;
	private long amount;
	private String depositorName;
	private String bankTxId;
	@Enumerated(EnumType.STRING)
	private Status status;
	private Long processedBy;
	private Long prepaymentPrincipal;
	private boolean prepaymentFull;

	protected Payment() {
	}

	Payment(Payments.Channel channel, LocalDate valueDate, LocalDate businessDate, OffsetDateTime receivedAt,
			Money amount, String depositorName, String bankTxId, Long processedBy) {
		this.channel = channel;
		this.valueDate = valueDate;
		this.businessDate = businessDate;
		this.receivedAt = receivedAt;
		this.amount = amount.won();
		this.depositorName = depositorName;
		this.bankTxId = bankTxId;
		this.processedBy = processedBy;
		this.status = Status.RECEIVED;
	}

	void allocateTo(long accountId) {
		this.accountId = accountId;
		this.status = Status.ALLOCATED;
	}

	void unmatch() {
		this.accountId = null;
		this.status = Status.UNMATCHED;
	}

	/** 금액 오입력: 실제로 들어오지 않은 입금 (R26) */
	void cancelAsInputError() {
		this.status = Status.CANCELLED;
	}

	void requestPrepayment(Long principal, boolean full) {
		this.prepaymentPrincipal = principal;
		this.prepaymentFull = full;
	}

	Payments.PaymentView view() {
		return new Payments.PaymentView(getId(), accountId, channel.name(), valueDate, businessDate, amount,
				depositorName, bankTxId, status.name());
	}

	Long accountId() {
		return accountId;
	}

	Payments.Channel channel() {
		return channel;
	}

	LocalDate valueDate() {
		return valueDate;
	}

	LocalDate businessDate() {
		return businessDate;
	}

	Money amount() {
		return Money.won(amount);
	}

	String depositorName() {
		return depositorName;
	}

	String bankTxId() {
		return bankTxId;
	}

	Status status() {
		return status;
	}

	Long processedBy() {
		return processedBy;
	}

	Long prepaymentPrincipal() {
		return prepaymentPrincipal;
	}

	boolean prepaymentFull() {
		return prepaymentFull;
	}
}
