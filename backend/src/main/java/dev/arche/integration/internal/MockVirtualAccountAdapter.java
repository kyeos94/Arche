package dev.arche.integration.internal;

import dev.arche.integration.VirtualAccountPort;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Mock 가상계좌 어댑터: HMAC-SHA256(공유 비밀, 요청 본문) hex 서명을 검증한다.
 * 실제 기관 연계 시 같은 포트의 다른 어댑터로 교체 (@Profile 또는 @ConditionalOnProperty).
 */
@Component
class MockVirtualAccountAdapter implements VirtualAccountPort {

	private final byte[] secret;

	MockVirtualAccountAdapter(@Value("${arche.integration.virtual-account.secret}") String secret) {
		this.secret = secret.getBytes(StandardCharsets.UTF_8);
	}

	@Override
	public boolean verify(String payload, String signature) {
		if (signature == null) {
			return false;
		}
		return MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8),
				signature.toLowerCase().getBytes(StandardCharsets.UTF_8));
	}

	String sign(String payload) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret, "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}
}
