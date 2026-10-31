package dev.arche.servicing.engine;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 소멸시효 (정책서 5장). 기산일: 기한이익상실일 또는 만기일. 5년, 판결·지급명령 확정 후 10년.
 * 입금(일부 변제)·압류·지급명령 신청 시 중단되고 그날부터 다시 기산한다.
 * 완성일 = 기산일 다음 날부터 세어 N년이 되는 날 (예: 2026-06-26 기산, 5년 → 2031-06-26).
 */
public final class LimitationPeriod {

	public static final List<Integer> ALERT_DAYS_BEFORE = List.of(180, 90, 30);

	private LimitationPeriod() {
	}

	public static @Nullable LocalDate completionDate(@Nullable LocalDate start, Collection<LocalDate> interruptions,
			@Nullable LocalDate judgmentConfirmedOn) {
		if (start == null && judgmentConfirmedOn == null) {
			return null;
		}
		LocalDate base = start;
		int years = 5;
		for (LocalDate i : interruptions) {
			if (base == null || i.isAfter(base)) {
				base = i;
			}
		}
		if (judgmentConfirmedOn != null && (base == null || !judgmentConfirmedOn.isBefore(base))) {
			base = judgmentConfirmedOn;
			years = 10;
		}
		return base.plusYears(years);
	}

	/** 임박 알림일 (완성 180·90·30일 전) */
	public static List<LocalDate> alertDates(LocalDate completion) {
		return ALERT_DAYS_BEFORE.stream().map(completion::minusDays).toList();
	}
}
