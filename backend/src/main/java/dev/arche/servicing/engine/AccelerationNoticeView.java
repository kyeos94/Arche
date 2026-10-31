package dev.arche.servicing.engine;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * 기한이익상실 통지 (R15). 실제 발송은 하지 않고 날짜만 기록.
 */
public record AccelerationNoticeView(
		LocalDate reasonDate,
		LocalDate sentOn,
		LocalDate deemedArrivalOn,
		@Nullable LocalDate actualArrivalOn,
		LocalDate accelerationDate,
		NoticeStatus status,
		@Nullable LocalDate closedOn) {
}
