package dev.arche.common.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * 암호화 대상 필드는 엔티티에 {@code @Convert(converter = EncryptedStringConverter.class)}로만 선언한다 (guide 10.2).
 * JPA가 컨버터를 직접 생성할 수도 있으므로 암호기는 기동 시 정적으로 등록한다.
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

	private static volatile FieldCipher cipher;

	static void register(FieldCipher c) {
		cipher = c;
	}

	@Override
	public String convertToDatabaseColumn(String plain) {
		return plain == null ? null : cipher().encrypt(plain);
	}

	@Override
	public String convertToEntityAttribute(String stored) {
		return stored == null ? null : cipher().decrypt(stored);
	}

	private static FieldCipher cipher() {
		FieldCipher c = cipher;
		if (c == null) {
			throw new IllegalStateException("FieldCipher가 등록되지 않았습니다");
		}
		return c;
	}
}
