package dev.arche.servicing.internal;

import dev.arche.common.InterestRate;
import dev.arche.common.Money;
import dev.arche.origination.LoanDisbursed;
import dev.arche.servicing.LoanAccounts;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 실행 이벤트 → 계약 생성 (guide 2.4). 커밋 후 비동기로 받으며, 실패하면 발행 기록이 미완료로 남아 재처리된다.
 * 신청 ID 유니크로 멱등.
 */
@Component
class OriginationListener {

	private final LoanAccounts accounts;

	OriginationListener(LoanAccounts accounts) {
		this.accounts = accounts;
	}

	@ApplicationModuleListener
	void on(LoanDisbursed e) {
		accounts.open(new LoanAccounts.OpenCommand(e.customerId(), e.productCode(), Money.won(e.amount()),
				new InterestRate(e.rate()), e.termMonths(), e.graceMonths(), e.dueDay(), e.disbursedOn(), null,
				e.applicationId()));
	}
}
