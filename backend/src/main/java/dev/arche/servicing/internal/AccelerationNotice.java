package dev.arche.servicing.internal;

import dev.arche.common.jpa.BaseEntity;
import dev.arche.servicing.engine.AccelerationNoticeView;
import dev.arche.servicing.engine.KnownNotice;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * 기한이익상실 통지 (R15). MVP는 실제로 발송하지 않고 발송일·도달일·상실 예정일만 기록한다.
 * 재계산으로 사유가 없어지면 삭제하지 않고 REVOKED(상실 후) 또는 CANCELLED(상실 전)로 남긴다 (R5).
 */
@Entity
@Table(name = "acceleration_notice")
class AccelerationNotice extends BaseEntity {

	private long accountId;
	private LocalDate reasonDate;
	private LocalDate sentOn;
	private LocalDate deemedArrivalOn;
	private LocalDate actualArrivalOn;
	private LocalDate accelerationDate;
	private String status;
	private LocalDate closedOn;

	protected AccelerationNotice() {
	}

	AccelerationNotice(long accountId, AccelerationNoticeView v) {
		this.accountId = accountId;
		this.reasonDate = v.reasonDate();
		this.actualArrivalOn = v.actualArrivalOn();
		apply(v);
	}

	void apply(AccelerationNoticeView v) {
		this.sentOn = v.sentOn();
		this.deemedArrivalOn = v.deemedArrivalOn();
		this.accelerationDate = v.accelerationDate();
		this.status = v.status().name();
		this.closedOn = v.closedOn();
	}

	/** 재생 결과 사유가 없어진 통지 */
	void voidOut(LocalDate today) {
		this.status = "EXECUTED".equals(status) ? "REVOKED" : "CANCELLED";
		this.closedOn = today;
	}

	void recordActualArrival(LocalDate date) {
		this.actualArrivalOn = date;
	}

	KnownNotice toKnown() {
		return new KnownNotice(reasonDate, actualArrivalOn);
	}

	boolean live() {
		return "PENDING".equals(status) || "EXECUTED".equals(status);
	}

	LocalDate reasonDate() {
		return reasonDate;
	}

	LocalDate sentOn() {
		return sentOn;
	}

	LocalDate deemedArrivalOn() {
		return deemedArrivalOn;
	}

	LocalDate actualArrivalOn() {
		return actualArrivalOn;
	}

	LocalDate accelerationDate() {
		return accelerationDate;
	}

	String status() {
		return status;
	}

	LocalDate closedOn() {
		return closedOn;
	}
}
