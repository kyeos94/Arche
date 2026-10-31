package dev.arche.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** 모듈 간 이벤트 리스너(@ApplicationModuleListener)와 일괄 처리용 비동기 실행 */
@Configuration
@EnableAsync
class AsyncConfig {
}
