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
}
