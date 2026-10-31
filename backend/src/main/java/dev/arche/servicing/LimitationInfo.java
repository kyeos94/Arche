package dev.arche.servicing;

import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 계약의 소멸시효 (정책서 5장, G13).
 *
 * @param start          기산일 (기한이익상실일, 만기일, 매입채권은 양수 시 승계한 기산일). 아직 없으면 null
 * @param completionDate 완성 예정일. 기산 전이면 null
 * @param alertDates     임박 알림일 (완성 180·90·30일 전)
 */
public record LimitationInfo(@Nullable LocalDate start, @Nullable String startReason, List<Event> events,
		@Nullable LocalDate completionDate, List<LocalDate> alertDates, boolean completed) {

	/** 중단·연장 사유 (입금, 지급명령 신청, 압류, 판결 확정) */
	public record Event(LocalDate date, String type, @Nullable String memo, boolean counted) {
	}
}
