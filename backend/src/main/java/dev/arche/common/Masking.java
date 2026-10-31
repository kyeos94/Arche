package dev.arche.common;

/**
 * 화면·엑셀·로그 기본 마스킹 (guide 10.4).
 */
public final class Masking {

	private Masking() {
	}

	/** 900101-1234567 → 900101-1****** */
	public static String residentNo(String plain) {
		String digits = plain.replaceAll("[^0-9]", "");
		if (digits.length() != 13) {
			return "******-*******";
		}
		return digits.substring(0, 6) + "-" + digits.charAt(6) + "******";
	}

	/** 010-1234-5678 → 010-****-5678 */
	public static String phone(String plain) {
		if (plain == null) {
			return null;
		}
		String digits = plain.replaceAll("[^0-9]", "");
		if (digits.length() < 8) {
			return "****";
		}
		return digits.substring(0, 3) + "-****-" + digits.substring(digits.length() - 4);
	}
}
