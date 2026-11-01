package dev.arche.communication.internal;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 연락 타임라인 (guide 13.2 우측 패널): 추심 연락 기록, 자동 문자, 수동 발송(문자·팩스·우편), 상담 메모를 시각순으로 합친다.
 * 같은 연락이 두 출처에 있으면(수동 발송·자동 문자가 남긴 연락 기록) 연락 기록 쪽을 뺀다.
 */
final class Timeline {

	enum Kind {
		CONTACT, AUTO_MESSAGE, DISPATCH, MEMO
	}

	/**
	 * @param channel CALL / SMS / VISIT / FAX / POST / STATUTORY_NOTICE / MEMO
	 * @param status  ALLOWED·SCHEDULED·BLOCKED (연락), SENT·SKIPPED (자동 문자), 발송 상태 (수동 발송)
	 */
	record Item(Kind kind, long sourceId, OffsetDateTime at, LocalDate date, String channel, String title,
			String summary, String status, String reason, String actor) {
	}

	record Page(List<Item> content, int page, int size, long totalElements, boolean hasNext) {
	}

	private static final Comparator<Item> NEWEST_FIRST = Comparator.comparing(Item::at).reversed()
			.thenComparing(Comparator.comparing(Item::kind).reversed())
			.thenComparing(Comparator.comparingLong(Item::sourceId).reversed());

	private Timeline() {
	}

	/**
	 * @param duplicatedContactIds 다른 출처가 이미 보여주는 연락 기록 ID
	 */
	static Page merge(List<Item> contacts, List<Item> autoMessages, List<Item> dispatches, List<Item> memos,
			Set<Long> duplicatedContactIds, int page, int size) {
		List<Item> all = Stream.of(contacts.stream().filter(c -> !duplicatedContactIds.contains(c.sourceId())),
				autoMessages.stream(), dispatches.stream(), memos.stream()).flatMap(s -> s).sorted(NEWEST_FIRST)
				.toList();
		int from = Math.min(all.size(), page * size);
		int to = Math.min(all.size(), from + size);
		return new Page(all.subList(from, to), page, size, all.size(), to < all.size());
	}
}
