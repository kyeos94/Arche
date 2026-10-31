package dev.arche.servicing.engine;

import dev.arche.common.Money;
import java.time.LocalDate;

/**
 * 회차 1개의 조회용 스냅샷.
 *
 * @param scheduleVersion 재산출할 때마다 1씩 증가
 * @param periodStart     계획 이자 계산 시작일 (직전 약정일 또는 실행·재산출일)
 */
public record Installment(
		int scheduleVersion,
		int seq,
		LocalDate periodStart,
		LocalDate dueDate,
		LocalDate graceEnd,
		Money principalDue,
		Money plannedInterest,
		Money principalPaid,
		InstallmentStatus status) {

	public Money plannedPayment() {
		return principalDue.plus(plannedInterest);
	}
}
