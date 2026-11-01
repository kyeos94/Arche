package dev.arche.reporting.internal;

import java.util.Locale;

/**
 * 연체 구간 (R66). 순서가 곧 악화 순서다 (CLOSED 제외).
 */
enum Bucket {

	CURRENT("정상"),
	D1_30("1~30일"),
	D31_60("31~60일"),
	D61_90("61~90일"),
	D91_PLUS("91일 이상"),
	ACCELERATED("기한이익상실"),
	/** 완제·상각·매각 등 종결 (장부 밖) */
	CLOSED("종결");

	private final String label;

	Bucket(String label) {
		this.label = label;
	}

	String label() {
		return label;
	}

	boolean open() {
		return this != CLOSED;
	}

	/**
	 * 상태 우선, 그다음 연체일수 (R66). 상태는 문자열로 받아 앞으로 추가될 상태도 처리한다:
	 * 종결 이름(PAID_OFF·CHARGED_OFF·SOLD·CLOSED 포함)은 종결, ACCELERATED는 기한이익상실, 나머지는 연체일수.
	 */
	static Bucket of(String status, int delinquencyDays) {
		String s = status == null ? "" : status.toUpperCase(Locale.ROOT);
		if (s.contains("PAID_OFF") || s.contains("CHARGED_OFF") || s.contains("SOLD") || s.contains("CLOSED")) {
			return CLOSED;
		}
		if (s.startsWith("ACCELERAT")) {
			return ACCELERATED;
		}
		if (delinquencyDays <= 0) {
			return CURRENT;
		}
		if (delinquencyDays <= 30) {
			return D1_30;
		}
		if (delinquencyDays <= 60) {
			return D31_60;
		}
		if (delinquencyDays <= 90) {
			return D61_90;
		}
		return D91_PLUS;
	}

	/** 이름으로 (저장된 값). 모르는 값은 null */
	static Bucket parse(String name) {
		for (Bucket b : values()) {
			if (b.name().equals(name)) {
				return b;
			}
		}
		return null;
	}
}
