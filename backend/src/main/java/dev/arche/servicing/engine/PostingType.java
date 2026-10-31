package dev.arche.servicing.engine;

/** 엔진이 만드는 원장 거래 유형 (LedgerEntry.type의 부분집합) */
public enum PostingType {
	DISBURSE,
	ACQUIRE,
	COST_CHARGE,
	/** 납입일 전 입금을 가수금으로 보관 */
	SUSPENSE_IN,
	/** 입금 즉시 충당 (연체 중, 자유상환, 매입채권, 상실·만기 후) */
	REPAYMENT,
	/** 약정일에 가수금 충당 */
	SUSPENSE_APPLY,
	/** 중도상환 (원금 + 수수료) */
	PREPAYMENT,
	/** 완제 후 남은 금액 반환 대기 */
	REFUND
}
