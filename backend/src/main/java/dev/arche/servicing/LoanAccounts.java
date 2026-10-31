package dev.arche.servicing;

import dev.arche.common.InterestRate;
import dev.arche.common.Money;
import java.time.LocalDate;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * 여신관리(servicing) 모듈 공개 API. 다른 모듈은 이 인터페이스로만 계약을 바꾼다.
 * 모든 변경은 계약 단위 락 안에서 이벤트를 기록하고 엔진으로 재생한다 (ADR 0002).
 */
public interface LoanAccounts {

	/**
	 * 계약 생성 (심사 없이 바로 실행, MVP 1장). termMonths는 분할 회차 수(거치 제외).
	 */
	record OpenCommand(long customerId, String productCode, Money principal, InterestRate rate, int termMonths,
			int graceMonths, int dueDay, LocalDate disbursedOn, @Nullable Long assignedStaffId,
			@Nullable Long applicationId) {
	}

	/** 매입채권 양수 등록 (정책서 1-1장, R17) */
	record AcquireCommand(long customerId, LocalDate acquiredOn, Money acquisitionPrice, String seller,
			LocalDate originalContractDate, InterestRate originalRate, @Nullable InterestRate originalLateRate,
			Money principal, Money preAcqInterest, Money cost, boolean interestCharged,
			@Nullable LocalDate originalAccelerationDate, @Nullable LocalDate limitationStartDate,
			@Nullable LocalDate judgmentConfirmedOn, @Nullable Long assignedStaffId) {
	}

	/** 입금과 함께 받는 중도상환 요청 (R7). principal이 null이면 전액 */
	record PrepaymentOption(@Nullable Money principal) {
	}

	AccountRef open(OpenCommand command);

	AccountRef acquire(AcquireCommand command);

	AccountRef get(long accountId);

	Optional<AccountRef> findByAccountNo(String accountNo);

	Optional<AccountRef> findByVirtualAccountNo(String virtualAccountNo);

	Optional<AccountRef> findByApplicationId(long applicationId);

	/**
	 * 입금 적용. bankDate는 은행 거래일자이며 기산일은 R13에 따라 정해진다. 과거 날짜면 소급 재계산 (guide 5.3).
	 */
	AccountRef applyPayment(long accountId, long paymentId, LocalDate bankDate, Money amount,
			@Nullable PrepaymentOption prepayment, @Nullable Long correctionId);

	/**
	 * 입금 취소: 그 입금이 없었던 것처럼 재생하고 달라진 원장 거래를 역분개한다 (R26). 완제 후에는 불가 (R31).
	 */
	AccountRef cancelPayment(long accountId, long paymentId, String reason, @Nullable Long correctionId);

	/** 일마감 ②~④: 열린 계약 전체를 nextDate 기준으로 재생해 연체·상실을 판정 (R24) */
	JudgeReport judgeAll(LocalDate nextDate);

	/** 일마감 ⑤: 계약 요약 잔액 = 원장 합계 검증. 불일치 계약 수 */
	int reconcile();

	/** 처리일 일보의 "정정" 항목: 이 업무일자에 등록된 과거 기산일 정정 역분개 합계 (R28) */
	Money correctionsPostedOn(LocalDate businessDate);

	record JudgeReport(int accounts, int newNotices, int accelerations) {
	}

	/** 유효한 입금 (취소 제외). 은행 거래일자 기준 */
	record PaymentRecord(LocalDate bankDate, Money amount) {
	}

	/** 소멸시효 (G13). 데이터 범위 검사는 호출자 책임 */
	LimitationInfo limitation(long accountId);

	/** 계약의 유효한 입금 내역 (분할납부 약속 판정 등, R18) */
	java.util.List<PaymentRecord> payments(long accountId);

	/** 완제되지 않은 계약 (추심 일 배치용) */
	java.util.List<AccountRef> openAccounts();
}
