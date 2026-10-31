package dev.arche.calendar;

import dev.arche.common.BusinessCalendar;
import java.time.LocalDate;

/**
 * 업무일자와 영업일 달력 (R6). 모든 계산·배치는 시스템 시계가 아니라 이 업무일자를 기준일로 받는다.
 */
public interface BusinessDates {

	/** 현재 업무일자 */
	LocalDate today();

	/** 휴일 테이블 기반 영업일 달력 (캐시) */
	BusinessCalendar calendar();

	/** 일마감 마지막 단계: 업무일자를 다음 날로 넘긴다 (R24 ⑥). 넘긴 뒤의 업무일자를 돌려준다 */
	LocalDate advance(LocalDate closingDate);
}
