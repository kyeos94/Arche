package dev.arche.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arche.collection.internal.LegalActionRules.Stage;
import dev.arche.collection.internal.LegalActionRules.Type;
import dev.arche.servicing.LoanAccounts.LegalEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 정책서 R51 법조치 단계와 소멸시효 연결 */
class LegalActionRulesTest {

	@ParameterizedTest(name = "{0}: {1} → {2} = {3}")
	@CsvSource(textBlock = """
			PAYMENT_ORDER, FILED,     ISSUED,      true
			PAYMENT_ORDER, FILED,     CONFIRMED,   true
			PAYMENT_ORDER, SERVED,    ISSUED,      false
			PAYMENT_ORDER, SERVED,    OBJECTED,    true
			PAYMENT_ORDER, OBJECTED,  CONFIRMED,   true
			PAYMENT_ORDER, CONFIRMED, WITHDRAWN,   false
			PAYMENT_ORDER, CONFIRMED, CLOSED,      true
			PAYMENT_ORDER, FILED,     RELEASED,    false
			PAYMENT_ORDER, FILED,     COMMENCED,   false
			SEIZURE,       FILED,     ORDERED,     true
			SEIZURE,       ORDERED,   RELEASED,    true
			SEIZURE,       COLLECTED, CLOSED,      true
			AUCTION,       FILED,     DISTRIBUTED, true
			AUCTION,       SOLD,      COMMENCED,   false
			AUCTION,       CLOSED,    DISTRIBUTED, false
			AUCTION,       WITHDRAWN, CLOSED,      false
			AUCTION,       FILED,     FILED,       false
			""")
	void 단계는_앞으로만_가고_끝난_법조치는_바꾸지_않는다(Type type, Stage from, Stage to, boolean allowed) {
		assertThat(LegalActionRules.canMove(type, from, to)).isEqualTo(allowed);
	}

	@Test
	void 신청은_소멸시효를_중단한다() {
		assertThat(LegalActionRules.interruptionOnFiling(Type.PAYMENT_ORDER)).isEqualTo(LegalEventType.PAYMENT_ORDER_FILED);
		assertThat(LegalActionRules.interruptionOnFiling(Type.SEIZURE)).isEqualTo(LegalEventType.SEIZURE);
		assertThat(LegalActionRules.interruptionOnFiling(Type.AUCTION)).isEqualTo(LegalEventType.SEIZURE);
	}

	@Test
	void 지급명령_확정만_10년_연장_사유다() {
		assertThat(LegalActionRules.eventOnStage(Type.PAYMENT_ORDER, Stage.CONFIRMED))
				.contains(LegalEventType.JUDGMENT_CONFIRMED);
		assertThat(LegalActionRules.eventOnStage(Type.PAYMENT_ORDER, Stage.SERVED)).isEmpty();
		assertThat(LegalActionRules.eventOnStage(Type.AUCTION, Stage.DISTRIBUTED)).isEmpty();
	}

	@Test
	void 취하_각하와_회수_전_압류_해제는_중단_효력을_없앤다() {
		assertThat(LegalActionRules.voidsInterruption(Stage.SERVED, Stage.WITHDRAWN)).isTrue();
		assertThat(LegalActionRules.voidsInterruption(Stage.FILED, Stage.DISMISSED)).isTrue();
		assertThat(LegalActionRules.voidsInterruption(Stage.ORDERED, Stage.RELEASED)).isTrue();
		assertThat(LegalActionRules.voidsInterruption(Stage.COLLECTED, Stage.RELEASED)).isFalse();
		assertThat(LegalActionRules.voidsInterruption(Stage.DISTRIBUTED, Stage.CLOSED)).isFalse();
	}

	@Test
	void 다음_단계_목록() {
		assertThat(LegalActionRules.nextStages(Type.SEIZURE, Stage.SERVED)).containsExactly(Stage.COLLECTED,
				Stage.CLOSED, Stage.WITHDRAWN, Stage.DISMISSED, Stage.RELEASED);
		assertThat(LegalActionRules.nextStages(Type.AUCTION, Stage.CLOSED)).isEmpty();
	}
}
