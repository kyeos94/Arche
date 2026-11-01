package dev.arche.treasury.internal;

import dev.arche.common.Money;
import dev.arche.common.crypto.EncryptedStringConverter;
import dev.arche.common.jpa.BaseEntity;
import dev.arche.integration.TransferPort;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 출금 1건 (guide 6.4). 작성(출납) → 승인(1,000만원 이하 1단계, 초과는 채권팀장 → 본부장 2단계) → 송금 → 결과 확정.
 * 받는 계좌번호는 암호문으로 두고 화면에는 마스킹 값만 보낸다.
 */
@Entity
@Table(name = "withdrawal")
class Withdrawal extends BaseEntity {

	/** 1,000만원 초과는 2단계 승인 (정책서 7장, 9.1) */
	static final Money TWO_STEP_OVER = Money.won(10_000_000);

	enum Type {
		/** 미확인 입금 반환 */
		UNMATCHED_REFUND,
		/** 완제 후 과입금 반환 */
		OVERPAYMENT_REFUND
	}

	enum Status {
		REQUESTED, FIRST_APPROVED,
		/** 최종 승인 후 송금 요청 중 */
		SENDING,
		/** 응답 불명(타임아웃) — 결과 조회로 확정 (R64) */
		UNKNOWN, COMPLETED, FAILED, REJECTED, CANCELLED;

		boolean open() {
			return this == REQUESTED || this == FIRST_APPROVED;
		}
	}

	private String withdrawalNo;
	@Enumerated(EnumType.STRING)
	private Type type;
	private Long accountId;
	private Long unmatchedId;
	private long amount;
	private String bankName;
	@Convert(converter = EncryptedStringConverter.class)
	@Column(name = "payee_account_enc")
	private String payeeAccount;
	private String payeeAccountMasked;
	private String holderName;
	private String reason;
	@Enumerated(EnumType.STRING)
	private Status status;
	private LocalDate requestedOn;
	private long requestedBy;
	private Long firstApprovedBy;
	private OffsetDateTime firstApprovedAt;
	private Long finalApprovedBy;
	private OffsetDateTime finalApprovedAt;
	private Long rejectedBy;
	private String rejectReason;
	private String idempotencyKey;
	private String providerTxId;
	private String resultCode;
	private String resultMessage;
	private OffsetDateTime sentAt;
	private OffsetDateTime completedAt;
	private int inquiryCount;

	protected Withdrawal() {
	}

	Withdrawal(String withdrawalNo, Type type, Long accountId, Long unmatchedId, Money amount, String bankName,
			String payeeAccount, String holderName, String reason, LocalDate requestedOn, long requestedBy) {
		this.withdrawalNo = withdrawalNo;
		this.type = type;
		this.accountId = accountId;
		this.unmatchedId = unmatchedId;
		this.amount = amount.won();
		this.bankName = bankName;
		this.payeeAccount = payeeAccount;
		this.payeeAccountMasked = TransferPort.maskAccount(payeeAccount);
		this.holderName = holderName;
		this.reason = reason;
		this.requestedOn = requestedOn;
		this.requestedBy = requestedBy;
		this.idempotencyKey = withdrawalNo + "-T1";
		this.status = Status.REQUESTED;
	}

	boolean twoStep() {
		return Money.won(amount).isGreaterThan(TWO_STEP_OVER);
	}

	void firstApprove(long staffId, OffsetDateTime at) {
		this.status = Status.FIRST_APPROVED;
		this.firstApprovedBy = staffId;
		this.firstApprovedAt = at;
	}

	void finalApprove(long staffId, OffsetDateTime at) {
		this.status = Status.SENDING;
		this.finalApprovedBy = staffId;
		this.finalApprovedAt = at;
	}

	void reject(long staffId, String reason) {
		this.status = Status.REJECTED;
		this.rejectedBy = staffId;
		this.rejectReason = reason;
	}

	void cancel() {
		this.status = Status.CANCELLED;
	}

	void result(TransferPort.TransferResult r, OffsetDateTime at, boolean inquiry) {
		if (inquiry) {
			inquiryCount++;
		} else {
			sentAt = at;
		}
		providerTxId = r.providerTxId();
		resultCode = r.resultCode();
		resultMessage = r.message();
		status = switch (r.status()) {
			case SUCCEEDED -> Status.COMPLETED;
			case FAILED -> Status.FAILED;
			case UNKNOWN -> Status.UNKNOWN;
		};
		if (status == Status.COMPLETED || status == Status.FAILED) {
			completedAt = at;
		}
	}

	TransferPort.TransferRequest transferRequest() {
		return new TransferPort.TransferRequest(idempotencyKey, bankName, payeeAccount, holderName, Money.won(amount));
	}

	@Override
	public String toString() {
		return "Withdrawal[" + withdrawalNo + ", " + type + ", " + status + "]";
	}

	String withdrawalNo() {
		return withdrawalNo;
	}

	Type type() {
		return type;
	}

	Long accountId() {
		return accountId;
	}

	Long unmatchedId() {
		return unmatchedId;
	}

	Money amount() {
		return Money.won(amount);
	}

	String bankName() {
		return bankName;
	}

	String payeeAccountMasked() {
		return payeeAccountMasked;
	}

	String holderName() {
		return holderName;
	}

	String reason() {
		return reason;
	}

	Status status() {
		return status;
	}

	LocalDate requestedOn() {
		return requestedOn;
	}

	long requestedBy() {
		return requestedBy;
	}

	Long firstApprovedBy() {
		return firstApprovedBy;
	}

	OffsetDateTime firstApprovedAt() {
		return firstApprovedAt;
	}

	Long finalApprovedBy() {
		return finalApprovedBy;
	}

	OffsetDateTime finalApprovedAt() {
		return finalApprovedAt;
	}

	Long rejectedBy() {
		return rejectedBy;
	}

	String rejectReason() {
		return rejectReason;
	}

	String idempotencyKey() {
		return idempotencyKey;
	}

	String providerTxId() {
		return providerTxId;
	}

	String resultCode() {
		return resultCode;
	}

	String resultMessage() {
		return resultMessage;
	}

	OffsetDateTime sentAt() {
		return sentAt;
	}

	OffsetDateTime completedAt() {
		return completedAt;
	}

	int inquiryCount() {
		return inquiryCount;
	}
}
