package dev.arche.servicing.engine;

import dev.arche.common.Money;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * 재생 입력 이벤트. ref는 원장 거래를 원 이벤트와 잇는 안정적인 키 (예: "PAY-12").
 */
public sealed interface LoanEvent {

	String ref();

	/** 입금. bankDate는 은행 거래일자이며 기산일은 엔진이 R13에 따라 정한다 */
	record Payment(String ref, LocalDate bankDate, Money amount) implements LoanEvent {
	}

	/** 중도상환 요청 (R7). principal이 null이면 전액 */
	record PrepaymentRequest(String ref, LocalDate requestedOn, @Nullable Money principal) implements LoanEvent {
	}

	/** 약정일 변경 신청 (R21) */
	record DueDayChange(String ref, LocalDate requestedOn, int newDueDay) implements LoanEvent {
	}

	/**
	 * 채무조정 체결 (R9·R23). 체결일까지 이자를 확정하고 감면액을 뺀 뒤, 미감면 이자·비용이 그날 모두 납부되면
	 * 조정 원금으로 원리금균등 스케줄을 새로 만든다 (체결일을 실행일로 보고 2장 규칙). 납부되지 않으면 체결하지 않는다.
	 */
	record Workout(String ref, LocalDate contractedOn, Money waiveLate, Money waiveNormal, Money waivePrincipal,
			int installments, int dueDay) implements LoanEvent {
	}

	/** 비용 적재 (법조치 비용 등) */
	record CostCharge(String ref, LocalDate chargedOn, Money amount) implements LoanEvent {
	}

	/**
	 * 상각 확정 (R39). 확정일까지 이자를 정산한 뒤 장부에서 제거한 금액을 CHARGE_OFF로 남긴다. 채권은 존속하며 확정일부터
	 * 이자를 붙이지 않는다. 그날의 입금을 먼저 충당한 뒤 처리한다
	 */
	record ChargeOff(String ref, LocalDate on) implements LoanEvent {
	}

	/**
	 * 매각 (R40). 매각일까지 이자를 정산하고 잔액을 양수인에게 넘긴다. 매각일(포함) 이후 은행 거래일자의 입금은 충당하지 않고
	 * 양수인 이전 대상(SALE_TRANSFER)으로 남긴다
	 */
	record Sale(String ref, LocalDate on) implements LoanEvent {
	}

	/**
	 * 환매 (R41). 매각 때 넘긴 잔액을 되살리고(매각 기간 이자 없음) 매각 직전 상태로 돌아간다. 매각 기간에 받은 입금은
	 * 환매일에 채무자 입금으로 충당하고, 남은 이전 대상은 해제한다
	 */
	record Repurchase(String ref, LocalDate on) implements LoanEvent {
	}

	/** 양수인에게 이전 대상 금액을 넘김 (R40) */
	record BuyerTransfer(String ref, LocalDate on, Money amount) implements LoanEvent {
	}

	/**
	 * 수기 조정 거래 (R42). 그날까지 이자를 정산한 뒤 항목별로 줄인다(미납액까지만). 비용만 늘릴 수 있다(costDelta 양수).
	 * 원금 감액은 마지막 회차부터 차감한다
	 */
	record Adjustment(String ref, LocalDate on, Money principalDown, Money normalDown, Money lateDown,
			Money costDelta, Money preAcqDown) implements LoanEvent {
	}

	/** 상계 원천 (R44) */
	enum SetoffSource {
		/** 완제 후 반환 대기 과입금 */
		REFUND,
		/** 가수금 */
		SUSPENSE
	}

	/** 상계 원천 계약: 반환 대기 또는 가수금에서 amount를 뺀다 (R44) */
	record SetoffOut(String ref, LocalDate on, Money amount, SetoffSource source) implements LoanEvent {
	}

	/**
	 * 상계 대상 계약: amount를 그날 입금처럼 충당한다(기산일 = 상계일, R13 유예 매핑 없음). 채무자의 변제가 아니므로
	 * 소멸시효 중단·약속 이행 판정에는 넣지 않는다 (R44)
	 */
	record SetoffIn(String ref, LocalDate on, Money amount) implements LoanEvent {
	}

	/**
	 * 이자 부과 중단 기간 [from, until) (R46: 개인회생 개시결정·파산 선고). until이 null이면 계속.
	 * 정상이자·가산이자 모두 붙이지 않는다
	 */
	record InterestSuspension(String ref, LocalDate from, @Nullable LocalDate until) implements LoanEvent {
	}
}
