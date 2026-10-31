package dev.arche.calendar.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.common.BusinessCalendar;
import dev.arche.common.BusinessException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.stream.Collectors;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.LockModeType;

interface BusinessDateRepository extends JpaRepository<BusinessDateRow, Short> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select b from BusinessDateRow b where b.id = 1")
	BusinessDateRow lockCurrent();
}

interface HolidayRepository extends JpaRepository<HolidayRow, LocalDate> {
}

@Service
class JpaBusinessDates implements BusinessDates {

	static final String CALENDAR_CACHE = "business-calendar";

	private final BusinessDateRepository dates;
	private final HolidayRepository holidays;
	private final Clock clock;

	JpaBusinessDates(BusinessDateRepository dates, HolidayRepository holidays, Clock clock) {
		this.dates = dates;
		this.holidays = holidays;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public LocalDate today() {
		return dates.findById((short) 1).orElseThrow().currentDate();
	}

	@Override
	@Cacheable(CALENDAR_CACHE)
	@Transactional(readOnly = true)
	public BusinessCalendar calendar() {
		return new BusinessCalendar(holidays.findAll().stream().map(HolidayRow::date).collect(Collectors.toSet()));
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
