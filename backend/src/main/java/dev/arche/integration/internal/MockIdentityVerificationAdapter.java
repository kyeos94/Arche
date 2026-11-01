package dev.arche.integration.internal;

import dev.arche.common.BusinessException;
import dev.arche.integration.IdentityVerificationPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Mock 본인인증 어댑터: 가짜 인증 화면(프런트 /mock-identity/{tx})에서 고객이 이름·생년월일을 넣고 성공·실패를 고른다.
 * 거래는 메모리에 10분 동안만 보관한다 (단일 서버 데모용. 실제 어댑터는 기관 콜백·결과 조회로 대체).
 */
@Component
class MockIdentityVerificationAdapter implements IdentityVerificationPort {

	static final Duration TTL = Duration.ofMinutes(10);
	private static final int MAX_PENDING = 10_000;

	enum State {
		PENDING, SUCCEEDED, FAILED
	}

	record Entry(String returnUrl, Instant createdAt, State state, VerifiedIdentity identity) {
	}

	private final Map<String, Entry> entries = new ConcurrentHashMap<>();
	private final Clock clock;

	MockIdentityVerificationAdapter(Clock clock) {
		this.clock = clock;
	}

	@Override
	public Session start(String returnUrl) {
		if (returnUrl == null || !returnUrl.startsWith("/") || returnUrl.startsWith("//")) {
			// 다른 사이트로 돌려보내는 열린 리다이렉트를 막는다
			throw new BusinessException("INVALID_RETURN_URL", "돌아올 주소는 같은 사이트의 경로여야 합니다");
		}
		purge();
		String tx = "MOCKID-" + UUID.randomUUID();
		entries.put(tx, new Entry(returnUrl, clock.instant(), State.PENDING, null));
		return new Session(tx, "/mock-identity/" + tx);
	}

	@Override
	public Optional<VerifiedIdentity> result(String transactionId) {
		Entry e = transactionId == null ? null : entries.remove(transactionId);
		if (e == null || expired(e) || e.state() != State.SUCCEEDED) {
			return Optional.empty();
		}
		return Optional.of(e.identity());
	}

	/** 가짜 인증 화면이 보여줄 거래 정보 */
	Optional<Entry> pending(String tx) {
		Entry e = entries.get(tx);
		return e == null || expired(e) || e.state() != State.PENDING ? Optional.empty() : Optional.of(e);
	}

	/** 가짜 인증 화면에서 성공·실패 선택. 돌아갈 주소를 돌려준다 */
	String complete(String tx, boolean success, String name, LocalDate birthDate) {
		Entry e = pending(tx).orElseThrow(
				() -> new BusinessException("IDENTITY_TX_NOT_FOUND", "인증 거래가 없거나 만료되었습니다"));
		Entry done = success && name != null && !name.isBlank() && birthDate != null
				? new Entry(e.returnUrl(), e.createdAt(), State.SUCCEEDED,
						new VerifiedIdentity(tx, name.trim(), birthDate, connectingInfo(name.trim(), birthDate)))
				: new Entry(e.returnUrl(), e.createdAt(), State.FAILED, null);
		entries.put(tx, done);
		return e.returnUrl() + (e.returnUrl().contains("?") ? "&" : "?") + "tx=" + tx;
	}

	/** 가상 연계값: 이름·생년월일의 SHA-256. 실제 기관 연계정보 형식과 무관 */
	static String connectingInfo(String name, LocalDate birthDate) {
		try {
			byte[] h = MessageDigest.getInstance("SHA-256")
					.digest(("mock-ci|" + name + "|" + birthDate).getBytes(StandardCharsets.UTF_8));
			return "MOCKCI-" + HexFormat.of().formatHex(h);
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private boolean expired(Entry e) {
		return e.createdAt().plus(TTL).isBefore(clock.instant());
	}

	private void purge() {
		entries.values().removeIf(this::expired);
		if (entries.size() > MAX_PENDING) {
			throw new BusinessException("IDENTITY_BUSY", "본인인증 요청이 너무 많습니다. 잠시 후 다시 시도하세요");
		}
	}
}
