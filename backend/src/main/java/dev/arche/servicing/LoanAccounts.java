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

	/** 직원 퇴사 (R75): 그 직원이 담당하는 완제 전 계약 수 */
	int countAssigned(long staffId);

	/** 직원 퇴사 (R75): 완제 전 담당 계약을 다른 직원에게 넘긴다. 넘긴 계약 수 */
	int reassignAssigned(long fromStaffId, long toStaffId);

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

	/**
	 * 자동 문자용 계약 상태 (정책서 6장). 마지막 재생 시점(일마감 후에는 판정일) 기준.
	 *
	 * @param delinquencyStartedOn 연체 1일째 = 가장 오래된 미납 회차의 납부 기한(휴일 유예 포함) 다음 날
	 * @param nextPaymentAmount    다음 회차 남은 원금 + 계획 이자 (예정액)
	 */
	record MessagingView(long id, String accountNo, long customerId, String status, String repaymentMethod,
			Long assignedStaffId, LocalDate nextDueDate, Long nextPaymentAmount, LocalDate delinquentSince,
			LocalDate delinquencyStartedOn, LocalDate paidOffOn, long payoffAmount) {
	}

	/** 완제되지 않은 계약 + 그날 완제된 계약 */
	java.util.List<MessagingView> messagingViews(LocalDate paidOffOn);

	MessagingView messagingView(long accountId);

	/**
	 * 양식(변제 독촉장·기한이익상실 통보서)에 채울 계약 값 (R72). 업무일자 기준으로 재생한 값이다.
	 *
	 * @param overdueAmount  지금 갚아야 할 금액 (R47과 같은 기준: 상실·만기 경과·매입채권은 완제 금액)
	 * @param noticeStatus   가장 최근 기한이익상실 통지의 상태 (PENDING·EXECUTED 등), 없으면 null
	 */
	record DocumentView(long id, String accountNo, long customerId, String productCode, boolean acquired, String status,
			LocalDate disbursedOn, Money initialPrincipal, InterestRate currentRate, Money principalBalance,
			Money overduePrincipal, Money unpaidNormalInterest, Money lateInterest, Money cost, Money suspense,
			Money overdueAmount, Money payoffAmount, @Nullable LocalDate delinquentSince, long delinquencyDays,
			int overdueInstallments, @Nullable LocalDate oldestOverdueDueDate, @Nullable LocalDate nextDueDate,
			@Nullable LocalDate acceleratedOn, @Nullable String noticeStatus, @Nullable LocalDate noticeReasonDate,
			@Nullable LocalDate noticeSentOn, @Nullable LocalDate noticeArrivalOn,
			@Nullable LocalDate noticeAccelerationDate, @Nullable String virtualAccountNo,
			@Nullable Long assignedStaffId, LocalDate asOf) {
	}

	DocumentView documentView(long accountId);

	// ------------------------------------------------------------------ 입금 배분·대위변제·법조치 (R47~R52)

	/**
	 * 일괄 입금 배분 기준 금액 (R47). 업무일자로 재생한 값이며 락 없이 계산한 추정치다 (실제 충당은 applyPayment가 락 안에서 다시 계산).
	 *
	 * @param overdue 지금 갚아야 할 연체금. 기한이익상실·만기 경과·매입채권은 완제 금액
	 * @param nextDue 다음 약정일 예정액 (남은 원금 + 계획 이자)
	 */
	record DueSnapshot(long accountId, String accountNo, long customerId, String productCode, String status,
			@Nullable Long assignedStaffId, LocalDate disbursedOn, @Nullable LocalDate delinquentSince,
			@Nullable LocalDate nextDueDate, Money overdue, Money nextDue, Money payoff) {
	}

	/** 고객의 완제 전 계약 (계약 id 순) */
	java.util.List<DueSnapshot> customerAccounts(long customerId);

	/** 비용 적재 (법조치 비용 등, guide 6.5). 업무일자에 COST 이벤트로 기록하고 재생한다. 완제 계약은 불가 */
	AccountRef chargeCost(long accountId, Money amount, String reason);

	/** 소멸시효 중단·연장 사유 (정책서 5장) */
	enum LegalEventType {
		PAYMENT_ORDER_FILED, SEIZURE, JUDGMENT_CONFIRMED
	}

	/** 소멸시효 중단 사유 기록. 기록 id를 돌려준다 (법조치 단계와 연결, R51) */
	long recordLimitationEvent(long accountId, LegalEventType type, LocalDate date, @Nullable String memo);

	/** 법조치 취하·각하 등으로 중단 효력이 없어진 사유를 무효로 표시 (행은 남김, R51) */
	void voidLimitationEvent(long accountId, long limitationEventId, LocalDate on, String reason);

	/** 채무자의 승인이 아닌 입금(보증인·제3자·법원 배당 등)은 소멸시효 중단 사유로 세지 않음 (R50) */
	void excludeFromLimitation(long accountId, long paymentId);

	/** 같은 고객의 완제·매각되지 않은 계약 (주어진 계약 포함). 추심 중지는 채무자 단위 (R45) */
	java.util.List<AccountRef> sameCustomerOpenAccounts(long accountId);

	/** 이자 부과 중단 시작 (R46 개인회생·파산). 과거 날짜면 소급 재계산. 재생 이벤트 id를 돌려준다 */
	long suspendInterest(long accountId, LocalDate from, String reason);

	/** 이자 부과 중단을 on 당일로 끝낸다 (그날부터 다시 이자, R46) */
	void endInterestSuspension(long accountId, long eventId, LocalDate on);

	/** 조정 금리 적용 (R46 공적 채무조정 확정): 현재 적용 이율 이하만, 재산출은 R20 */
	AccountRef lowerRate(long accountId, InterestRate rate, LocalDate validFrom, String reason);
}
