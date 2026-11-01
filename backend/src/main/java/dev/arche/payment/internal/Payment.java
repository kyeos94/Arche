package dev.arche.payment.internal;

import dev.arche.common.Money;
import dev.arche.common.crypto.EncryptedStringConverter;
import dev.arche.common.jpa.BaseEntity;
import dev.arche.payment.Payments;
import dev.arche.payment.internal.split.AllocationStrategy;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 입금 1건. valueDate는 은행 거래일자이며, 계약의 기산일(R13)은 servicing이 정한다.
 * 일괄 배분(R48)은 원 입금(SPLIT, 계약 없음) + 계약별 하위 입금(parentPaymentId)으로 저장한다.
 */
@Entity
@Table(name = "payment")
class Payment extends BaseEntity {

	enum Status {
		RECEIVED, ALLOCATED, UNMATCHED, REVERSED, CANCELLED,
		/** 일괄 배분된 원 입금: 돈은 하위 입금들로 계약에 충당됨 (R48) */
		SPLIT
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
	private Long parentPaymentId;
	@Enumerated(EnumType.STRING)
	private AllocationStrategy.Method splitMethod;
	@Enumerated(EnumType.STRING)
	private PayerType payerType = PayerType.CUSTOMER;
	@Convert(converter = EncryptedStringConverter.class)
	@Column(name = "payer_name_enc")
	private String payerName;
	private String payerRef;
	private Long legalActionId;

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

	/** 금액 오입력: 실제로 들어오지 않은 입금 (R26). 일괄 배분 취소 시 하위 입금도 이 상태 (돈은 원 입금에 있음, R48) */
	void cancelAsInputError() {
		this.status = Status.CANCELLED;
	}

	void requestPrepayment(Long principal, boolean full) {
		this.prepaymentPrincipal = principal;
		this.prepaymentFull = full;
	}

	void markSplit(AllocationStrategy.Method method) {
		this.accountId = null;
		this.status = Status.SPLIT;
		this.splitMethod = method;
	}

	void childOf(Payment parent) {
		this.parentPaymentId = parent.getId();
		this.payer(parent.payer());
		this.legalActionId = parent.legalActionId;
	}

	void payer(Payer payer) {
		this.payerType = payer.type();
		this.payerName = payer.name();
		this.payerRef = payer.ref();
	}

	void linkLegalAction(Long legalActionId) {
		this.legalActionId = legalActionId;
	}

	/**
	 * 채무자 본인의 변제(승인)인가 (R50). 보증인·제3자 입금과 법원 배당금·공탁금은 아니다.
	 */
	boolean acknowledgesDebt() {
		return payerType == PayerType.CUSTOMER && !channel.court();
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

	Long parentPaymentId() {
		return parentPaymentId;
	}

	AllocationStrategy.Method splitMethod() {
		return splitMethod;
	}

	Payer payer() {
		return payerType == null || payerType == PayerType.CUSTOMER ? Payer.CUSTOMER
				: new Payer(payerType, payerName, payerRef);
	}

	Long legalActionId() {
		return legalActionId;
	}

	@Override
	public String toString() {
		return "Payment[id=" + getId() + ", status=" + status + ", amount=" + amount + "]";
	}
}
