package dev.arche.servicing.engine;

import dev.arche.common.Money;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * 채무조정 결과.
 *
 * @param concluded  미감면 이자를 체결일에 모두 받아 체결됐는지 (R23)
 * @param restoredOn 조정 후 3회차 연속 미납으로 감면액을 되살리고 기한이익상실로 복귀한 날 (R9)
 */
public record WorkoutView(String ref, LocalDate contractedOn, boolean concluded, Money waivedLate,
		Money waivedNormal, Money waivedPrincipal, Money requiredPayment, @Nullable LocalDate restoredOn) {
}
