package dev.arche.servicing.internal;

import dev.arche.common.jpa.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * 수기 조정·상계·상태 되돌리기 결재 (R42~R44). 요청은 잔액을 바꾸지 않고, 승인(작성자 ≠ 승인자)할 때 재생 이벤트를 만든다.
 * 완제 취소(REVERT_PAYOFF)는 승인되면 APPLIED 상태로 입금 취소 1건을 허용하고, 그 취소가 처리되면 CONSUMED (R43).
 */
@Entity
@Table(name = "adjustment_request")
class AdjustmentRequest extends BaseEntity {

	enum Type {
		/** 수기 조정 거래 (R42) */
		ADJUSTMENT,
		/** 상계 (R44) */
		SETOFF,
		/** 상각 취소 (R43) */
		REVERT_CHARGE_OFF,
		/** 매각 취소 (R43) */
		REVERT_SALE,
		/** 상계 취소 (R43) */
		REVERT_SETOFF,
		/** 완제 취소: 완제·매각·상각 후 입금 취소 1건 허용 (R43) */
		REVERT_PAYOFF
	}

	enum Status {
		REQUESTED, APPLIED, REJECTED, CONSUMED
	}

	private String requestNo;
	@Enumerated(EnumType.STRING)
	private Type type;
	private long accountId;
	private Long sourceAccountId;
	private String setoffSource;
	private long principalDown;
	private long normalDown;
	private long lateDown;
	private long costDelta;
	private long preAcqDown;
	private long amount;
	private Long targetEventId;
	private String reason;
	@Enumerated(EnumType.STRING)
	private Status status;
	private long requestedBy;
	private LocalDate requestedOn;
	private Long decidedBy;
	private LocalDate decidedOn;
	private Long appliedEventId;
	private LocalDate consumedOn;

	protected AdjustmentRequest() {
	}

	AdjustmentRequest(String requestNo, Type type, long accountId, String reason, long requestedBy,
			LocalDate requestedOn) {
		this.requestNo = requestNo;
		this.type = type;
		this.accountId = accountId;
		this.reason = reason;
		this.requestedBy = requestedBy;
		this.requestedOn = requestedOn;
		this.status = Status.REQUESTED;
	}

	AdjustmentRequest amounts(long principalDown, long normalDown, long lateDown, long costDelta, long preAcqDown) {
		this.principalDown = principalDown;
		this.normalDown = normalDown;
		this.lateDown = lateDown;
		this.costDelta = costDelta;
		this.preAcqDown = preAcqDown;
		return this;
	}

	AdjustmentRequest setoff(long sourceAccountId, String source, long amount) {
		this.sourceAccountId = sourceAccountId;
		this.setoffSource = source;
		this.amount = amount;
		return this;
	}

	AdjustmentRequest target(Long eventId) {
		this.targetEventId = eventId;
		return this;
	}

	void applied(long by, LocalDate on, Long eventId) {
		this.status = Status.APPLIED;
		this.decidedBy = by;
		this.decidedOn = on;
		this.appliedEventId = eventId;
	}

	void rejected(long by, LocalDate on) {
		this.status = Status.REJECTED;
		this.decidedBy = by;
		this.decidedOn = on;
	}

	void consumed(LocalDate on) {
		this.status = Status.CONSUMED;
		this.consumedOn = on;
	}

	String requestNo() {
		return requestNo;
	}

	Type type() {
		return type;
	}

	long accountId() {
		return accountId;
	}

	Long sourceAccountId() {
		return sourceAccountId;
	}

	String setoffSource() {
		return setoffSource;
	}

	long principalDown() {
		return principalDown;
	}

	long normalDown() {
		return normalDown;
	}

	long lateDown() {
		return lateDown;
	}

	long costDelta() {
		return costDelta;
	}

	long preAcqDown() {
		return preAcqDown;
	}

	long amount() {
		return amount;
	}

	Long targetEventId() {
		return targetEventId;
	}

	String reason() {
		return reason;
	}

	Status status() {
		return status;
	}

	long requestedBy() {
		return requestedBy;
	}

	LocalDate requestedOn() {
		return requestedOn;
	}

	Long decidedBy() {
		return decidedBy;
	}

	LocalDate decidedOn() {
		return decidedOn;
	}

	Long appliedEventId() {
		return appliedEventId;
	}

	LocalDate consumedOn() {
		return consumedOn;
	}
}
