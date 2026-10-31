package dev.arche.integration.internal;

import dev.arche.integration.MessagingPort;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Mock 문자 어댑터: 실제로 보내지 않고 로그만 남긴다. 본문·번호 등 개인정보는 로그에 찍지 않는다 (guide 10.4).
 */
@Component
class MockMessagingAdapter implements MessagingPort {

	private static final Logger log = LoggerFactory.getLogger(MockMessagingAdapter.class);

	@Override
	public Sent send(long customerId, String body) {
		String id = "MOCK-" + UUID.randomUUID();
		log.info("[Mock 문자] 고객 #{} 에게 {}자 발송 → {}", customerId, body.length(), id);
		return new Sent(id);
	}
}
