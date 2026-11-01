package dev.arche.payment.internal;

import dev.arche.common.BusinessException;
import org.jspecify.annotations.Nullable;

/**
 * 실제로 돈을 낸 사람 (R49). 이름은 개인정보라 toString에 평문을 내지 않는다.
 *
 * @param ref 관계·보증 계약 등 자유 메모 (보증인 관리 엔티티에 의존하지 않음)
 */
record Payer(PayerType type, @Nullable String name, @Nullable String ref) {

	static final Payer CUSTOMER = new Payer(PayerType.CUSTOMER, null, null);

	static Payer of(@Nullable PayerType type, @Nullable String name, @Nullable String ref) {
		if (type == null || type == PayerType.CUSTOMER) {
			return CUSTOMER;
		}
		if (name == null || name.isBlank()) {
			throw new BusinessException("PAYER_NAME_REQUIRED", "보증인·제3자 입금은 입금자 이름을 입력하세요 (R49)");
		}
		if (name.trim().length() > 50 || (ref != null && ref.length() > 100)) {
			throw new BusinessException("PAYER_INVALID", "입금자 이름은 50자, 참조 메모는 100자까지입니다");
		}
		return new Payer(type, name.trim(), ref == null || ref.isBlank() ? null : ref.trim());
	}

	boolean isCustomer() {
		return type == PayerType.CUSTOMER;
	}

	/** 홍길동 → 홍*동, 두 글자는 홍* */
	static @Nullable String mask(@Nullable String name) {
		if (name == null || name.isEmpty()) {
			return name;
		}
		int n = name.length();
		if (n == 1) {
			return "*";
		}
		if (n == 2) {
			return name.charAt(0) + "*";
		}
		return name.charAt(0) + "*".repeat(n - 2) + name.charAt(n - 1);
	}

	@Override
	public String toString() {
		return "Payer[type=" + type + ", name=" + mask(name) + "]";
	}
}
