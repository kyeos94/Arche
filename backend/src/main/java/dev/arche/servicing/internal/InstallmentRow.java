package dev.arche.servicing.internal;

import dev.arche.common.jpa.BaseEntity;
import dev.arche.servicing.engine.Installment;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * 회차 (스케줄 버전별). 재산출로 대체된 버전은 REPLACED로 남기고 삭제하지 않는다 (guide 3.3).
 */
@Entity
@Table(name = "installment")
class InstallmentRow extends BaseEntity {

	private long accountId;
	private int scheduleVersion;
	private int seq;
	private LocalDate periodStart;
	private LocalDate dueDate;
	private LocalDate graceEnd;
	private long principalDue;
	private long plannedInterest;
	private long principalPaid;
	private String status;

	protected InstallmentRow() {
	}

	InstallmentRow(long accountId, Installment i) {
		this.accountId = accountId;
		this.scheduleVersion = i.scheduleVersion();
		this.seq = i.seq();
		update(i);
	}

	void update(Installment i) {
		this.periodStart = i.periodStart();
		this.dueDate = i.dueDate();
		this.graceEnd = i.graceEnd();
		this.principalDue = i.principalDue().won();
		this.plannedInterest = i.plannedInterest().won();
		this.principalPaid = i.principalPaid().won();
		this.status = i.status().name();
	}

	void markReplaced() {
		this.status = "REPLACED";
	}

	int scheduleVersion() {
		return scheduleVersion;
	}

	int seq() {
		return seq;
	}

	LocalDate periodStart() {
		return periodStart;
	}

	LocalDate dueDate() {
		return dueDate;
	}

	LocalDate graceEnd() {
		return graceEnd;
	}

	long principalDue() {
		return principalDue;
	}

	long plannedInterest() {
		return plannedInterest;
	}

	long principalPaid() {
		return principalPaid;
	}

	String status() {
		return status;
	}
}
