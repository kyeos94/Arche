package dev.arche.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 블라인드 인덱스: HMAC-SHA256(검색키, 정규화된 평문)의 hex 64자 (guide 10.3).
 */
public final class BlindIndexer {

	private final SecretKeySpec key;

	public BlindIndexer(byte[] key) {
		this.key = new SecretKeySpec(key, "HmacSHA256");
	}

	public String index(String plain) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(key);
			return HexFormat.of().formatHex(mac.doFinal(normalize(plain).getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("인덱스 생성 실패", e);
		}
	}

	/** 구분 기호를 없애고 대문자로 ("900101-1234567"과 "9001011234567"은 같은 값) */
	static String normalize(String plain) {
		return plain.replaceAll("[^0-9A-Za-z]", "").toUpperCase();
	}
}
