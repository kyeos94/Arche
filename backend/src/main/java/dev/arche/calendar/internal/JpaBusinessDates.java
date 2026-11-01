package dev.arche.calendar.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.common.BusinessCalendar;
import dev.arche.common.BusinessException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.LockModeType;

interface BusinessDateRepository extends JpaRepository<BusinessDateRow, Short> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select b from BusinessDateRow b where b.id = 1")
	BusinessDateRow lockCurrent();
}

interface HolidayRepository extends JpaRepository<HolidayRow, LocalDate> {

	java.util.List<HolidayRow> findByHolidayDateBetweenOrderByHolidayDate(LocalDate from, LocalDate to);
}

@Service
class JpaBusinessDates implements BusinessDates {

	/** 달력과 그 달력을 읽을 때의 휴일 변경 이력 버전 */
	private record Snapshot(long version, BusinessCalendar calendar) {
	}

	private final BusinessDateRepository dates;
	private final HolidayRepository holidays;
	private final JdbcClient jdbc;
	private final Clock clock;
	private volatile @Nullable Snapshot snapshot;

	JpaBusinessDates(BusinessDateRepository dates, HolidayRepository holidays, JdbcClient jdbc, Clock clock) {
		this.dates = dates;
		this.holidays = holidays;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public LocalDate today() {
		return dates.findById((short) 1).orElseThrow().currentDate();
	}

	/**
	 * 휴일 달력. 메모리에 두되, 휴일 변경 이력(holiday_change_log)의 마지막 id가 바뀌었으면 다시 읽는다 (R73).
	 * 그래서 휴일 관리에서 바꾼 휴일은 서버가 여러 대여도 다음 계산(입금 충당·일마감 재생)부터 쓰인다.
	 * 버전 확인은 기본키 최댓값 조회 한 번이라 계약마다 불러도 가볍다.
	 */
	@Override
	@Transactional(readOnly = true)
	public BusinessCalendar calendar() {
		long version = jdbc.sql("select coalesce(max(id), 0) from holiday_change_log").query(Long.class).single();
		Snapshot s = snapshot;
		if (s == null || s.version() != version) {
			s = new Snapshot(version,
					new BusinessCalendar(holidays.findAll().stream().map(HolidayRow::date).collect(Collectors.toSet())));
			snapshot = s;
		}
		return s.calendar();
	}

	@Override
	@Transactional
	public LocalDate advance(LocalDate closingDate) {
		BusinessDateRow row = dates.lockCurrent();
		if (!row.currentDate().equals(closingDate)) {
			throw new BusinessException("BUSINESS_DATE_MISMATCH",
					"현재 업무일자(" + row.currentDate() + ")와 마감 일자(" + closingDate + ")가 다릅니다");
		}
		LocalDate next = closingDate.plusDays(1);
		row.advanceTo(next, OffsetDateTime.now(clock));
		return next;
	}
}
