package dev.arche.operation.internal;

import dev.arche.calendar.BusinessDates;
import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 밀린 일마감 따라잡기. 서버가 꺼져 있어 23:00 일마감이 빠졌으면, 기동할 때 업무일자가 오늘이 될 때까지 하루씩 마감한다.
 * 업무일자를 건너뛰지 않고 실제 마감을 반복하므로 연체·기한이익상실·알림 판정이 빠지지 않는다 (R6·R24).
 * 데모 데이터 생성 다음에 실행한다.
 */
@Component
@Order(20)
@ConditionalOnProperty(name = "arche.closing.catch-up", havingValue = "true", matchIfMissing = true)
class ClosingCatchUp implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(ClosingCatchUp.class);
	/** 잘못된 설정으로 끝없이 돌지 않게 (약 3년) */
	static final int MAX_DAYS = 1_100;

	private final DailyClosingService closing;
	private final BusinessDates businessDates;
	private final Clock clock;

	ClosingCatchUp(DailyClosingService closing, BusinessDates businessDates, Clock clock) {
		this.closing = closing;
		this.businessDates = businessDates;
		this.clock = clock;
	}

	@Override
	public void run(ApplicationArguments args) {
		LocalDate today = LocalDate.now(clock);
		LocalDate from = businessDates.today();
		int days = 0;
		while (businessDates.today().isBefore(today) && days < MAX_DAYS) {
			closing.close();
			days++;
		}
		if (days > 0) {
			log.info("밀린 일마감 {}일 처리: 업무일자 {} → {}", days, from, businessDates.today());
		}
	}
}
