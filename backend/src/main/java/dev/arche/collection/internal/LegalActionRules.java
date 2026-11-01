package dev.arche.collection.internal;

import dev.arche.servicing.LoanAccounts;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 법조치 종류·단계와 소멸시효 연결 (정책서 R51). 순수 규칙이며 법원·사건번호 형식은 다루지 않는다.
 */
final class LegalActionRules {

	enum Type {
		/** 지급명령 */
		PAYMENT_ORDER(Stage.FILED, Stage.ISSUED, Stage.SERVED, Stage.OBJECTED, Stage.CONFIRMED),
		/** 압류 (채권·유체동산 등 대상 구분 없이 일반형) */
		SEIZURE(Stage.FILED, Stage.ORDERED, Stage.SERVED, Stage.COLLECTED),
		/** 경매 */
		AUCTION(Stage.FILED, Stage.COMMENCED, Stage.SALE_SCHEDULED, Stage.SOLD, Stage.DISTRIBUTED);

		private final List<Stage> progress;

		Type(Stage... progress) {
			this.progress = List.of(progress);
		}

		List<Stage> progress() {
			return progress;
		}
	}

	enum Stage {
		/** 신청 */
		FILED,
		/** 지급명령 발령 */
		ISSUED,
		/** 송달 */
		SERVED,
		/** 이의신청 (소송으로 이행) */
		OBJECTED,
		/** 확정 (지급명령 확정 또는 이의 후 판결 확정) */
		CONFIRMED,
		/** 압류 결정 */
		ORDERED,
		/** 추심·전부 등 회수 */
		COLLECTED,
		/** 경매 개시 결정 */
		COMMENCED,
		/** 매각기일 지정 */
		SALE_SCHEDULED,
		/** 매각 허가 */
		SOLD,
		/** 배당 */
		DISTRIBUTED,
		/** 종결 */
		CLOSED,
		/** 취하 */
		WITHDRAWN,
		/** 각하·기각 */
		DISMISSED,
		/** 압류 해제 */
		RELEASED
	}

	static final Set<Stage> TERMINAL = EnumSet.of(Stage.CLOSED, Stage.WITHDRAWN, Stage.DISMISSED, Stage.RELEASED);

	private LegalActionRules() {
	}

	/**
	 * R51 단계 전이: 종류별 진행 순서에서 앞으로만(중간 단계 생략 가능). 종결은 언제나, 취하·각하는 확정 전까지,
	 * 압류 해제는 압류에서만. 끝난 법조치는 바꾸지 않는다.
	 */
	static boolean canMove(Type type, Stage from, Stage to) {
		if (TERMINAL.contains(from) || from == to) {
			return false;
		}
		return switch (to) {
			case CLOSED -> true;
			case WITHDRAWN, DISMISSED -> from != Stage.CONFIRMED;
			case RELEASED -> type == Type.SEIZURE;
			default -> type.progress().indexOf(to) > type.progress().indexOf(from)
					&& type.progress().contains(to);
		};
	}

	static List<Stage> nextStages(Type type, Stage from) {
		List<Stage> result = new ArrayList<>();
		for (Stage s : Stage.values()) {
			if (canMove(type, from, s)) {
				result.add(s);
			}
		}
		return result;
	}

	/** 신청이 남기는 소멸시효 중단 사유 (정책서 5장): 지급명령 신청, 압류 신청, 경매 신청(압류 효력) */
	static LoanAccounts.LegalEventType interruptionOnFiling(Type type) {
		return switch (type) {
			case PAYMENT_ORDER -> LoanAccounts.LegalEventType.PAYMENT_ORDER_FILED;
			case SEIZURE, AUCTION -> LoanAccounts.LegalEventType.SEIZURE;
		};
	}

	/** 단계가 남기는 시효 사유: 지급명령(이의 후 판결 포함) 확정 → 10년 */
	static Optional<LoanAccounts.LegalEventType> eventOnStage(Type type, Stage stage) {
		return type == Type.PAYMENT_ORDER && stage == Stage.CONFIRMED
				? Optional.of(LoanAccounts.LegalEventType.JUDGMENT_CONFIRMED)
				: Optional.empty();
	}

	/**
	 * 신청으로 생긴 중단 효력이 없어지는 단계 (민법상 취하·각하된 신청은 중단 효력 없음). 회수 후 압류 해제는 그대로 둔다.
	 */
	static boolean voidsInterruption(Stage from, Stage to) {
		return to == Stage.WITHDRAWN || to == Stage.DISMISSED || (to == Stage.RELEASED && from != Stage.COLLECTED);
	}

	static String label(Type type) {
		return switch (type) {
			case PAYMENT_ORDER -> "지급명령";
			case SEIZURE -> "압류";
			case AUCTION -> "경매";
		};
	}
}
