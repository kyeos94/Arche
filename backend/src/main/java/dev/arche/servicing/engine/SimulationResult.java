package dev.arche.servicing.engine;

import dev.arche.common.Money;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * 기준일(asOf)까지 재생한 계약 상태.
 *
 * @param unpaidNormalInterest  정산 이벤트에서 확정됐으나 미납인 정상이자
 * @param accruedNormalInterest 마지막 정산일부터 기준일까지 발생한 미확정 정상이자 (조회용, R1)
 * @param payoffAmount          기준일 완제 금액 (R8)
 * @param chargedOffOn          상각 확정일 (R39)
 * @param soldOn                매각 중이면 매각일 (R40, 환매하면 null)
 * @param transferPending       양수인에게 아직 넘기지 않은 매각 후 입금 (R40)
 */
public record SimulationResult(
		LocalDate asOf,
		AccountStatus status,
		Money principalBalance,
		Money suspense,
		Money cost,
		Money preAcqInterest,
		Money unpaidNormalInterest,
		Money unpaidLateInterest,
		Money accruedNormalInterest,
		Money accruedLateInterest,
		LocalDate lastSettledOn,
		@Nullable LocalDate delinquentSince,
		@Nullable LocalDate acceleratedOn,
		@Nullable LocalDate maturityDate,
		@Nullable Money installmentAmount,
		int scheduleVersion,
		List<Installment> installments,
		List<Installment> previousVersions,
		List<Posting> postings,
		List<AccelerationNoticeView> notices,
		Money totalPaid,
		Money refundPending,
		Money prepaymentFeeIfRepaidToday,
		Money payoffAmount,
		@Nullable LocalDate paidOffOn,
		List<String> warnings,
		List<WorkoutView> workouts,
		@Nullable LocalDate chargedOffOn,
		@Nullable LocalDate soldOn,
		Money transferPending) {

	/** 연체일수 = 기준일 − 가장 오래된 미납 회차의 약정일 */
	public long delinquencyDays() {
		return delinquentSince == null ? 0 : ChronoUnit.DAYS.between(delinquentSince, asOf);
	}

	/** 기준일에 갚아야 할 정상이자 (확정 미납 + 미확정 발생분) */
	public Money outstandingNormalInterest() {
		return unpaidNormalInterest.plus(accruedNormalInterest);
	}

	public Money outstandingLateInterest() {
		return unpaidLateInterest.plus(accruedLateInterest);
	}

	public Optional<Installment> installment(int seq) {
		return installments.stream().filter(i -> i.seq() == seq).findFirst();
	}

	/** 기준일 이후 처음 도래하는 미납 회차 */
	public Optional<Installment> nextInstallment() {
		return installments.stream().filter(i -> !i.dueDate().isBefore(asOf))
				.filter(i -> i.status() != InstallmentStatus.PAID).findFirst();
	}

	public List<AccelerationNoticeView> noticesWith(NoticeStatus status) {
		return notices.stream().filter(n -> n.status() == status).toList();
	}
}
