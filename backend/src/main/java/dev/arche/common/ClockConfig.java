package dev.arche.common;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시각은 주입된 Clock에서만 얻는다 (CLAUDE.md 절대 규칙). 테스트는 고정 시계로 교체.
 */
@Configuration
class ClockConfig {

	static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	@Bean
	Clock clock() {
		return Clock.system(SEOUL);
	}
}
