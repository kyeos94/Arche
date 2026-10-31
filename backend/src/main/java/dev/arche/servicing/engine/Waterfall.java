package dev.arche.servicing.engine;

import dev.arche.common.Money;

/**
 * 충당 폭포: 입금액에서 미납 항목을 정해진 순서대로 떼어 간다 (정책서 4장 충당 순서, G10).
 */
public final class Waterfall {

	private Money left;

	public Waterfall(Money amount) {
		this.left = amount;
	}

	/** 미납액 due 중 남은 돈으로 갚을 수 있는 만큼 떼어 가고 그 금액을 돌려준다 */
	public Money take(Money due) {
		Money taken = left.min(due.max(Money.ZERO));
		left = left.minus(taken);
		return taken;
	}

	public Money left() {
		return left;
	}
}
