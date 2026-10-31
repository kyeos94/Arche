package dev.arche.servicing.engine;

import dev.arche.common.Money;
import java.time.LocalDate;
import java.util.List;

/**
 * 원장 거래 1건. 금액 필드는 그 거래로 충당(감소)된 금액이며, suspense는 가수금 증감(+ 보관, − 사용).
 * normalAccrued·lateAccrued는 이 정산 이벤트에서 확정된 이자 (R1).
 */
public record Posting(
		int seq,
		LocalDate valueDate,
		PostingType type,
		String sourceRef,
		Money amount,
		Money principal,
		Money normalInterest,
		Money lateInterest,
		Money cost,
		Money fee,
		Money preAcqInterest,
		Money suspense,
		Money refund,
		Money normalAccrued,
		Money lateAccrued,
		Money principalBalanceAfter,
		List<InstallmentAllocation> allocations) {

	/** 회차별 원금 충당 내역 */
	public record InstallmentAllocation(int installmentSeq, Money principal) {
	}

	/** 재계산 비교용 키: 같은 원 이벤트·같은 유형·같은 기산일이면 같은 거래 */
	public String key() {
		return type + "|" + sourceRef + "|" + valueDate;
	}

	/** 금액 내용이 같은지 (seq·잔액 제외) */
	public boolean sameAmounts(Posting o) {
		return amount.equals(o.amount) && principal.equals(o.principal) && normalInterest.equals(o.normalInterest)
				&& lateInterest.equals(o.lateInterest) && cost.equals(o.cost) && fee.equals(o.fee)
				&& preAcqInterest.equals(o.preAcqInterest) && suspense.equals(o.suspense) && refund.equals(o.refund)
				&& allocations.equals(o.allocations);
	}
}
