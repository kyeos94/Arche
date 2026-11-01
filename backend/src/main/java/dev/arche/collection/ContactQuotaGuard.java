package dev.arche.collection;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 추심 연락 한도: 계약 1건당 직전 7일(당일 포함) 동안 7회 (정책서 6장, G14).
 * 전화·문자·방문 모두 1회로 세고, 법정·계약상 통지는 한도에서 제외한다. 차단된 시도는 세지 않는다.
 * 팩스·우편으로 보내는 추심 안내도 1회로 센다 (R59).
 */
public final class ContactQuotaGuard {

	public static final int LIMIT = 7;
	public static final int WINDOW_DAYS = 7;

	public enum Channel {
		CALL, SMS, VISIT,
		/** 추심 목적 팩스·우편 (R59) */
		FAX, POST,
		/** 납입 안내(연체 전), 기한이익상실 통지, 양도 통지 등 — 한도 제외 */
		STATUTORY_NOTICE
	}

	public record Attempt(LocalDate date, Channel channel) {
	}

	public record Decision(Attempt attempt, int countInWindow, boolean allowed) {
	}

	private final List<LocalDate> allowedContacts = new ArrayList<>();

	public ContactQuotaGuard() {
	}

	/** 이미 허용된(한도에 세는) 연락 날짜로 시작 */
	public ContactQuotaGuard(java.util.Collection<LocalDate> priorAllowed) {
		allowedContacts.addAll(priorAllowed);
	}

	/** 시도 하나를 판정하고, 허용되면 기록한다 */
	public Decision attempt(Attempt a) {
		if (a.channel() == Channel.STATUTORY_NOTICE) {
			return new Decision(a, -1, true);
		}
		LocalDate windowStart = a.date().minusDays(WINDOW_DAYS - 1);
		int count = (int) allowedContacts.stream().filter(d -> !d.isBefore(windowStart) && !d.isAfter(a.date()))
				.count();
		boolean allowed = count < LIMIT;
		if (allowed) {
			allowedContacts.add(a.date());
		}
		return new Decision(a, count, allowed);
	}

	/** 화면 표시용: 그날 남은 연락 가능 횟수 */
	public int remaining(LocalDate date) {
		LocalDate windowStart = date.minusDays(WINDOW_DAYS - 1);
		long used = allowedContacts.stream().filter(d -> !d.isBefore(windowStart) && !d.isAfter(date)).count();
		return (int) Math.max(0, LIMIT - used);
	}
}
