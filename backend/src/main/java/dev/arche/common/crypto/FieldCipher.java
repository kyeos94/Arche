package dev.arche.common.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM 컬럼 암호화. 저장 형식 "v1:" + base64(iv(12) || ciphertext || tag(16)).
 * 접두어의 키 버전으로 키 교체 시 구·신 키를 함께 읽을 수 있다.
 */
public final class FieldCipher {

	private static final String PREFIX = "v1:";
	private static final int IV_BYTES = 12;
	private static final int TAG_BITS = 128;

	private final SecretKeySpec key;
	private final SecureRandom random = new SecureRandom();

	public FieldCipher(byte[] key) {
		if (key.length != 32) {
			throw new IllegalArgumentException("AES-256 키는 32바이트여야 합니다");
		}
		this.key = new SecretKeySpec(key, "AES");
	}

	public String encrypt(String plain) {
		try {
			byte[] iv = new byte[IV_BYTES];
			random.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
			byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
			return PREFIX + Base64.getEncoder()
					.encodeToString(ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("암호화 실패", e);
		}
	}

	public String decrypt(String stored) {
		if (!stored.startsWith(PREFIX)) {
			throw new IllegalArgumentException("알 수 없는 키 버전");
		}
		try {
			byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
			return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("복호화 실패", e);
		}
	}
}
