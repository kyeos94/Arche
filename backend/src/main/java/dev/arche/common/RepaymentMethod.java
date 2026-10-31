package dev.arche.common;

/**
 * 상환방식 (정책서 1장).
 */
public enum RepaymentMethod {
	/** 원리금균등 (P1) */
	EQUAL_INSTALLMENT,
	/** 원금균등 (P3) */
	EQUAL_PRINCIPAL,
	/** 만기일시 (P2) */
	BULLET,
	/** 거치 후 원리금균등 (P4) */
	GRACE_THEN_INSTALLMENT,
	/** 자유상환 (P5) */
	REVOLVING,
	/** 매입채권 — 스케줄 없이 입금만큼 충당 (N1) */
	ACQUIRED;

	/** 회차 원금 스케줄이 있는 분할상환 방식 (기한이익상실 사유 R4 적용 대상) */
	public boolean isInstallment() {
		return this == EQUAL_INSTALLMENT || this == EQUAL_PRINCIPAL || this == GRACE_THEN_INSTALLMENT;
	}

	/** 입금 시 즉시 정산하고 남는 돈을 원금에 충당하는 방식 (가수금 없음) */
	public boolean settlesOnPayment() {
		return this == REVOLVING || this == ACQUIRED;
	}
}
