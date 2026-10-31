package dev.arche.payment;

import dev.arche.common.Money;
import java.time.LocalDate;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * 입금 모듈 공개 API (가상계좌 웹훅·일마감이 사용).
 */
public interface Payments {

	enum Channel {
		VIRTUAL_ACCOUNT, BANK_TRANSFER, AUTO_DEBIT, CASH
	}

	/**
	 * @param accountId null이면 미확인 입금으로 보관
	 * @param bankTxId  은행 거래고유번호. 같은 번호는 한 번만 처리 (중복 통지 방지)
	 */
	record Deposit(@Nullable Long accountId, Channel channel, LocalDate bankDate, Money amount,
			@Nullable String depositorName, String bankTxId) {
	}

	record PaymentView(long id, @Nullable Long accountId, String channel, LocalDate valueDate, LocalDate businessDate,
			long amount, @Nullable String depositorName, String bankTxId, String status) {
	}

	record DailyTotals(int allocatedCount, Money allocatedTotal, Money unmatchedOpenTotal) {
	}

	/** 중복 거래고유번호면 DUPLICATE 예외 */
	PaymentView register(Deposit deposit);

	Optional<PaymentView> findByBankTxId(String bankTxId);

	/** 일보용 집계: 이 업무일자에 등록되어 계약에 충당된 입금 + 현재 미확인 입금 잔액 */
	DailyTotals totalsFor(LocalDate businessDate);
}
