package dev.arche.servicing.engine;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * 이미 저장된 기한이익상실 통지. 재생 시 같은 사유 발생일의 통지를 그대로 이어 쓴다.
 *
 * @param actualArrivalOn 배달 조회로 확인된 실제 도달일 (R3)
 */
public record KnownNotice(LocalDate reasonDate, @Nullable LocalDate actualArrivalOn) {
}
