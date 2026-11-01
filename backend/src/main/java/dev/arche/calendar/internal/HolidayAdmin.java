package dev.arche.calendar.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.common.BusinessException;
import dev.arche.staff.StaffDirectory;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 휴일 관리 (R73, 가정·검수 필요). 평일을 휴일로 지정하거나 지정한 휴일을 해제한다.
 * <ul>
 * <li>업무일자 다음 날부터만 바꿀 수 있다. 업무일자와 그 이전은 이미 마감·계산에 쓰였으므로 고치지 않는다</li>
 * <li>토·일요일은 항상 휴일이라 바꿀 수 없다 ({@link dev.arche.common.BusinessCalendar})</li>
 * <li>업무일자 + 3년까지 (잘못 누른 먼 미래 날짜 방지)</li>
 * <li>바뀐 휴일은 다음 재생(입금 충당·일마감·조건 변경)부터 반영된다. 진행 중인 기한이익상실 통지의 도달 간주일·상실 예정일도
 * 그때 다시 계산된다(R15)</li>
 * </ul>
 */
@Service
class HolidayAdminService {

	static final int MAX_YEARS_AHEAD = 3;

	record Holiday(LocalDate date, String name) {
	}

	record YearView(int year, LocalDate businessDate, LocalDate editableFrom, LocalDate editableTo,
			List<Holiday> holidays) {
	}

	record Change(long id, LocalDate date, String action, String name, LocalDate businessDate, String changedBy,
			OffsetDateTime changedAt) {
	}

	private final HolidayRepository holidays;
	private final BusinessDates businessDates;
	private final StaffDirectory staff;
	private final JdbcClient jdbc;
	private final Clock clock;

	HolidayAdminService(HolidayRepository holidays, BusinessDates businessDates, StaffDirectory staff, JdbcClient jdbc,
			Clock clock) {
		this.holidays = holidays;
		this.businessDates = businessDates;
		this.staff = staff;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	YearView year(int year) {
		if (year < 2000 || year > 2100) {
			throw new BusinessException("YEAR_INVALID", "연도를 확인하세요");
		}
		LocalDate today = businessDates.today();
		List<Holiday> rows = holidays
				.findByHolidayDateBetweenOrderByHolidayDate(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))
				.stream().map(h -> new Holiday(h.date(), h.name())).toList();
		return new YearView(year, today, today.plusDays(1), today.plusYears(MAX_YEARS_AHEAD), rows);
	}

	@Transactional
	Holiday add(LocalDate date, @Nullable String name) {
		LocalDate today = businessDates.today();
		check(date, today);
		String label = name == null ? "" : name.strip();
		if (label.isEmpty() || label.length() > 50) {
			throw new BusinessException("HOLIDAY_NAME", "휴일 이름을 1~50자로 입력하세요");
		}
		if (holidays.existsById(date)) {
			throw new BusinessException("HOLIDAY_EXISTS", date + "은 이미 휴일입니다");
		}
		holidays.save(new HolidayRow(date, label));
		log(date, "ADD", label, today);
		return new Holiday(date, label);
	}

	@Transactional
	void remove(LocalDate date) {
		LocalDate today = businessDates.today();
		check(date, today);
		HolidayRow row = holidays.findById(date)
				.orElseThrow(() -> new BusinessException("HOLIDAY_NOT_FOUND", date + "은 지정된 휴일이 아닙니다"));
		holidays.delete(row);
		log(date, "REMOVE", row.name(), today);
	}

	@Transactional(readOnly = true)
	List<Change> changes(int limit) {
		return jdbc.sql("""
				select id, holiday_date, action, name, business_date, changed_by, changed_at
				from holiday_change_log order by id desc limit :limit
				""").param("limit", Math.clamp(limit, 1, 500))
				.query((rs, n) -> new Change(rs.getLong("id"), rs.getObject("holiday_date", LocalDate.class),
						rs.getString("action"), rs.getString("name"), rs.getObject("business_date", LocalDate.class),
						rs.getString("changed_by"), rs.getObject("changed_at", OffsetDateTime.class)))
				.list();
	}

	/** 바꿀 수 있는 날짜인지 (테스트에서 직접 부른다) */
	static void check(LocalDate date, LocalDate businessDate) {
		if (!date.isAfter(businessDate)) {
			throw new BusinessException("HOLIDAY_PAST",
					"업무일자(" + businessDate + ") 다음 날부터 바꿀 수 있습니다. 지난 날짜는 이미 마감·계산에 쓰였습니다");
		}
		if (date.isAfter(businessDate.plusYears(MAX_YEARS_AHEAD))) {
			throw new BusinessException("HOLIDAY_TOO_FAR", MAX_YEARS_AHEAD + "년 뒤까지만 지정할 수 있습니다");
		}
		DayOfWeek dow = date.getDayOfWeek();
		if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
			throw new BusinessException("HOLIDAY_WEEKEND", "토·일요일은 항상 휴일이라 바꿀 수 없습니다");
		}
	}

	/** 이 이력의 마지막 id가 달력 캐시의 버전이다 (JpaBusinessDates.calendar) */
	private void log(LocalDate date, String action, String name, LocalDate today) {
		jdbc.sql("""
				insert into holiday_change_log (holiday_date, action, name, business_date, changed_by, changed_at)
				values (:d, :a, :n, :b, :by, :at)
				""").param("d", date).param("a", action).param("n", name).param("b", today)
				.param("by", staff.current().loginId()).param("at", OffsetDateTime.now(clock)).update();
	}
}

@RestController
@RequestMapping("/api/admin/holidays")
@PreAuthorize("hasAuthority('admin.manage')")
@Validated
class HolidayAdminController {

	record AddRequest(@NotNull LocalDate date, String name) {
	}

	record RemoveRequest(@NotNull LocalDate date) {
	}

	private final HolidayAdminService service;

	HolidayAdminController(HolidayAdminService service) {
		this.service = service;
	}

	@GetMapping
	HolidayAdminService.YearView year(@RequestParam int year) {
		return service.year(year);
	}

	@PostMapping
	HolidayAdminService.YearView add(@jakarta.validation.Valid @RequestBody AddRequest r) {
		service.add(r.date(), r.name());
		return service.year(r.date().getYear());
	}

	@PostMapping("/removal")
	HolidayAdminService.YearView remove(@jakarta.validation.Valid @RequestBody RemoveRequest r) {
		service.remove(r.date());
		return service.year(r.date().getYear());
	}

	@GetMapping("/changes")
	List<HolidayAdminService.Change> changes(@RequestParam(defaultValue = "100") int limit) {
		return service.changes(limit);
	}
}
