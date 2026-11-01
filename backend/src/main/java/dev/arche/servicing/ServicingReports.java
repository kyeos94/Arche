package dev.arche.servicing;

import dev.arche.staff.DataScope;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * 보고서(reporting 모듈)용 읽기 전용 공개 API (ADR 0009). 원장·계약을 바꾸지 않는다.
 * 상태·거래유형은 문자열로 넘겨, 새 값이 생겨도 보고서 쪽이 이름으로 처리할 수 있게 한다.
 */
public interface ServicingReports {

	/**
	 * 원장 거래 1건 (분개 원천). 금액 필드는 {@code LedgerEntry} 그대로이며 역분개는 부호가 반대다.
	 *
	 * @param reversedType 역분개(REVERSAL)이면 원 거래의 유형, 아니면 null
	 * @param acquired     양수(ACQUIRE) 거래와 그 역분개이면 양수 시점 잔액 분해(역분개는 음수), 아니면 null
	 */
	record LedgerLine(long entryId, long accountId, String accountNo, String type, @Nullable String reversedType,
			LocalDate valueDate, LocalDate businessDate, @Nullable Long paymentId, @Nullable Long correctionId,
			long amount, long principal, long normalInterest, long lateInterest, long cost, long fee,
			long preAcqInterest, long suspense, long refund, long normalAccrued, long lateAccrued,
			@Nullable Acquired acquired) {

		/** 역분개면 원 거래유형, 아니면 자기 유형 */
		public String effectiveType() {
			return reversedType != null ? reversedType : type;
		}

		public boolean reversal() {
			return reversedType != null;
		}
	}

	record Acquired(long principal, long preAcqInterest, long cost) {
	}

	/** 이 업무일자(처리일)에 기록된 원장 거래, id 순 */
	List<LedgerLine> ledgerPostedOn(LocalDate businessDate);

	/** 계약 요약 잔액 (마지막 재생 시점, 일마감 후에는 판정일 기준) */
	record AccountBalance(long accountId, String accountNo, String productCode, String origin, String status,
			int delinquencyDays, long principalBalance, long unpaidNormalInterest, long unpaidLateInterest,
			long costBalance, long preAcqInterest, long suspenseBalance, long refundPending,
			long accruedNormalInterest, long accruedLateInterest) {
	}

	/** 모든 계약의 요약 잔액, id 순 */
	List<AccountBalance> balances();

	/** 계약 목록 행 (엑셀 다운로드) */
	record LoanListRow(long id, String accountNo, long customerId, String productCode, String status,
			long principalBalance, long payoffAmount, int delinquencyDays, @Nullable LocalDate nextDueDate,
			@Nullable Long assignedStaffId) {
	}

	/**
	 * 계약 목록 화면과 같은 검색 조건(상태, 검색어 = 계약번호 부분 일치 또는 주민등록번호 일치)과 데이터 범위로 계약을 id 역순으로 순회한다.
	 * 한 번에 다 읽지 않고 나눠 읽는다(스트리밍 다운로드).
	 */
	void forEachLoan(@Nullable String status, @Nullable String q, DataScope scope, Consumer<LoanListRow> consumer);
}
