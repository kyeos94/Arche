package dev.arche.payment.internal;

import dev.arche.common.jpa.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * 입금 취소·정정 묶음 (R26~R30). 취소와 재입금을 하나의 정정번호·하나의 트랜잭션으로 처리한다.
 * 마감 후 정정은 시스템관리자 요청 → 채권팀장 승인 2단계 (R28).
 */
@Entity
@Table(name = "payment_correction")
class PaymentCorrection extends BaseEntity {

	enum Type {
		/** 단독 취소: 돈은 미확인 입금으로 돌아감 */
		CANCEL,
		/** 잘못된 계약 충당 → 다른 계약으로 이동 (G28) */
		MOVE,
		/** 금액 오입력 → 올바른 금액으로 같은 기산일 재입력 (G26) */
		AMOUNT_FIX
	}

	enum Status {
		REQUESTED, APPROVED, APPLIED, REJECTED
	}

	private String correctionNo;
	@Enumerated(EnumType.STRING)
	private Type type;
	private long originalPaymentId;
	private Long newPaymentId;
	private Long targetAccountId;
	private Long correctedAmount;
	private String reason;
	private boolean afterClose;
	private long requestedBy;
	private Long approvedBy;
	@Enumerated(EnumType.STRING)
	private Status status;
	private LocalDate requestedOn;
	private LocalDate appliedOn;

	protected PaymentCorrection() {
	}

	PaymentCorrection(String correctionNo, Type type, long originalPaymentId, Long targetAccountId,
			Long correctedAmount, String reason, boolean afterClose, long requestedBy, LocalDate requestedOn) {
		this.correctionNo = correctionNo;
		this.type = type;
		this.originalPaymentId = originalPaymentId;
		this.targetAccountId = targetAccountId;
		this.correctedAmount = correctedAmount;
		this.reason = reason;
		this.afterClose = afterClose;
		this.requestedBy = requestedBy;
		this.requestedOn = requestedOn;
		this.status = Status.REQUESTED;
	}

	void approve(long staffId) {
		this.approvedBy = staffId;
		this.status = Status.APPROVED;
	}

	void reject(long staffId) {
		this.approvedBy = staffId;
		this.status = Status.REJECTED;
	}

	void applied(LocalDate on, Long newPaymentId) {
		this.status = Status.APPLIED;
		this.appliedOn = on;
		this.newPaymentId = newPaymentId;
	}

	String correctionNo() {
		return correctionNo;
	}

	Type type() {
		return type;
	}

	long originalPaymentId() {
		return originalPaymentId;
	}

	Long newPaymentId() {
		return newPaymentId;
	}

	Long targetAccountId() {
		return targetAccountId;
	}

	Long correctedAmount() {
		return correctedAmount;
	}

	String reason() {
		return reason;
	}

	boolean afterClose() {
		return afterClose;
	}

	long requestedBy() {
		return requestedBy;
	}

	Long approvedBy() {
		return approvedBy;
	}

	Status status() {
		return status;
	}

	LocalDate requestedOn() {
		return requestedOn;
	}

	LocalDate appliedOn() {
		return appliedOn;
	}
}
