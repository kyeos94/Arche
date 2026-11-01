package dev.arche.integration.internal;

import dev.arche.integration.LoanPlatformPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Mock 대출 비교 플랫폼 어댑터: 결과 통보를 실제로 보내지 않고 최근 100건을 메모리에 보관한다 (데모 화면에서 확인).
 * platformRef가 "FAIL"로 시작하면 전달 실패를 흉내 낸다 (재통보 시나리오).
 */
@Component
class MockLoanPlatformAdapter implements LoanPlatformPort {

	private static final Logger log = LoggerFactory.getLogger(MockLoanPlatformAdapter.class);
	private static final int KEEP = 100;

	record Received(Instant receivedAt, ResultNotice notice, boolean delivered) {
	}

	private final String code;
	private final byte[] apiKey;
	private final Clock clock;
	private final Deque<Received> received = new ArrayDeque<>();

	MockLoanPlatformAdapter(@Value("${arche.integration.platform.mock.code}") String code,
			@Value("${arche.integration.platform.mock.api-key}") String apiKey, Clock clock) {
		this.code = code;
		this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);
		this.clock = clock;
	}

	@Override
	public String platformCode() {
		return code;
	}

	@Override
	public boolean authenticate(String key) {
		return key != null && MessageDigest.isEqual(apiKey, key.getBytes(StandardCharsets.UTF_8));
	}

	@Override
	public synchronized Delivery notifyResult(ResultNotice notice) {
		boolean ok = !notice.platformRef().startsWith("FAIL");
		received.addFirst(new Received(clock.instant(), notice, ok));
		while (received.size() > KEEP) {
			received.removeLast();
		}
		log.info("[Mock 플랫폼 {}] 결과 통보 {} {} → {}", code, notice.applicationNo(), notice.result(),
				ok ? "전달" : "실패");
		return new Delivery(ok, ok ? "MOCKPF-" + UUID.randomUUID() : null);
	}

	synchronized List<Received> recent() {
		return new ArrayList<>(received);
	}
}
