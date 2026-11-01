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
	/** 채무조정 감면 (금액 = 줄어든 이자·원금) */
	WAIVER,
	/**
	 * 잔액 조정. 항목 금액이 양수면 줄어든 금액, 음수면 늘어난 금액. 채무조정 미체결·실패의 감면액 부활(음수)과
	 * 수기 조정 거래(R42)
	 */
	ADJUSTMENT,
	/** 완제 후 남은 금액 반환 대기 */
	REFUND,
	/**
	 * 상각 확정 (R39). 장부에서 제거한 금액의 메모 거래: 항목 금액 = 제거한 원금·이자·비용. 채권(청구 잔액)은 존속하므로
	 * 원금 잔액은 줄지 않는다
	 */
	CHARGE_OFF,
	/** 매각 (R40). 양수인에게 넘긴 원금·이자·비용. 매각 후 계약 잔액은 0 */
	SALE,
	/** 환매 (R41). 매각 때 넘긴 잔액을 되살림 (항목 금액은 음수) */
	REPURCHASE,
	/** 매각 후 입금의 양수인 이전 대상 (R40). 금액 양수 = 이전 대상 증가, 음수 = 이전 완료·해제 */
	SALE_TRANSFER,
	/** 상계 (R44). 원천 계약에서는 반환 대기·가수금 감소, 대상 계약에서는 입금처럼 충당 */
	SETOFF
}
