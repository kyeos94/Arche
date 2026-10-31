package dev.arche.common;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/**
 * 영업일 달력. 토·일요일과 등록된 공휴일을 휴일로 본다 (정책서 8장, R25).
 */
public final class BusinessCalendar {

	private final Set<LocalDate> holidays;

	public BusinessCalendar(Set<LocalDate> holidays) {
		this.holidays = Set.copyOf(holidays);
	}

	public boolean isBusinessDay(LocalDate date) {
		DayOfWeek dow = date.getDayOfWeek();
		return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !holidays.contains(date);
	}

	/** 그 날이 영업일이면 그 날, 아니면 다음 영업일 (휴일 유예 납부 기한) */
	public LocalDate onOrNextBusinessDay(LocalDate date) {
		LocalDate d = date;
		while (!isBusinessDay(d)) {
			d = d.plusDays(1);
		}
		return d;
	}

	/** date 다음 영업일부터 세어 n번째 영업일 (정책서 R15) */
	public LocalDate plusBusinessDays(LocalDate date, int n) {
		LocalDate d = date;
		int count = 0;
		while (count < n) {
			d = d.plusDays(1);
			if (isBusinessDay(d)) {
				count++;
			}
		}
		return d;
	}
}
