package dev.arche.payment.internal;

import dev.arche.common.Money;
import dev.arche.common.crypto.EncryptedStringConverter;
import dev.arche.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * 대위변제 기록 (R49): 보증인·제3자가 채무자 대신 낸 입금. 구상권 대상 금액 = 그 입금액.
 * 회사는 기록만 남기며, 입금이 취소·정정되면 이 기록도 취소하고 필요하면 새로 만든다.
 */
@Entity
@Table(name = "subrogation_claim")
class SubrogationClaim extends BaseEntity {

	enum Status {
		ACTIVE, CANCELLED
	}

	private long paymentId;
	private long accountId;
	@Enumerated(EnumType.STRING)
	private PayerType payerType;
	@Convert(converter = EncryptedStringConverter.class)
	@Column(name = "payer_name_enc")
	private String payerName;
	private String payerRef;
	private LocalDate valueDate;
	private long amount;
	@Enumerated(EnumType.STRING)
	private Status status;
	private LocalDate recordedOn;
	private LocalDate cancelledOn;
	private String cancelReason;

	protected SubrogationClaim() {
	}

	SubrogationClaim(Payment p, long accountId, LocalDate recordedOn) {
		this.paymentId = p.getId();
		this.accountId = accountId;
		this.payerType = p.payer().type();
		this.payerName = p.payer().name();
		this.payerRef = p.payer().ref();
		this.valueDate = p.valueDate();
		this.amount = p.amount().won();
		this.status = Status.ACTIVE;
		this.recordedOn = recordedOn;
	}

	void cancel(LocalDate on, String reason) {
		this.status = Status.CANCELLED;
		this.cancelledOn = on;
		this.cancelReason = reason;
	}

	long paymentId() {
		return paymentId;
	}

	long accountId() {
		return accountId;
	}

	PayerType payerType() {
		return payerType;
	}

	String payerName() {
		return payerName;
	}

	String payerRef() {
		return payerRef;
	}

	LocalDate valueDate() {
		return valueDate;
	}

	Money amount() {
		return Money.won(amount);
	}

	Status status() {
		return status;
	}

	LocalDate recordedOn() {
		return recordedOn;
	}

	LocalDate cancelledOn() {
		return cancelledOn;
	}

	String cancelReason() {
		return cancelReason;
	}

	@Override
	public String toString() {
		return "SubrogationClaim[id=" + getId() + ", paymentId=" + paymentId + ", amount=" + amount + "]";
	}
}
