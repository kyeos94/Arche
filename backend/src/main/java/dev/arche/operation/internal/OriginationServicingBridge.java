package dev.arche.operation.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.origination.ServicingHistory;
import dev.arche.servicing.AccountRef;
import dev.arche.servicing.LoanAccounts;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 심사(origination)가 여신관리 정보를 직접 참조하면 모듈 순환이 되므로, 두 모듈을 모두 아는 operation이 연결한다 (ADR 0007).
 */
@Component
class OriginationServicingBridge implements ServicingHistory {

	/** 내부 불량 이력으로 보는 계약 상태 (R53) */
	static final Set<String> ADVERSE = Set.of("DELINQUENT", "ACCELERATED", "WORKOUT", "CHARGED_OFF", "SOLD");

	private final LoanAccounts accounts;
	private final BusinessDates businessDates;

	OriginationServicingBridge(LoanAccounts accounts, BusinessDates businessDates) {
		this.accounts = accounts;
		this.businessDates = businessDates;
	}

	@Override
	public List<String> adverseAccounts(long customerId) {
		return accounts.messagingViews(businessDates.today()).stream()
				.filter(v -> v.customerId() == customerId && ADVERSE.contains(v.status()))
				.map(v -> v.accountNo() + " " + v.status()).toList();
	}

	@Override
	public Optional<LocalDate> paidOffOn(long applicationId) {
		return accounts.findByApplicationId(applicationId).map(AccountRef::paidOffOn);
	}
}
