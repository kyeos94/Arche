package dev.arche.collection;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * 추심 연락 판정·기록 공개 API (communication 모듈의 문자·팩스·우편 발송과 연락 타임라인용).
 * 판정 순서는 연락 기록 화면과 같다: 법정·계약상 통지는 항상 허용 → 소멸시효 완성 → 연락 유예 → 연락 가능 시간 → 7일 7회 (R59).
 */
public interface CollectionContacts {

	/**
	 * @param result       ALLOWED / SCHEDULED(연락 가능 시간 밖 문자 → contactDate 08:00) / BLOCKED
	 * @param contactLogId 기록한 연락 이력 ID
	 */
	record Verdict(LocalDate contactDate, String result, int countInWindow, @Nullable String reason,
			@Nullable Long contactLogId) {

		public boolean blocked() {
			return "BLOCKED".equals(result);
		}
	}

	/**
	 * 연락 1건을 판정하고 기록한다(차단된 시도도 기록). statutory면 법정·계약상 통지로 기록해 한도·유예·시간 제한을 받지 않는다.
	 * 우편(POST)은 접수 시각이 연락 시각이 아니므로 연락 가능 시간 제한을 받지 않는다 (R59).
	 */
	Verdict contact(long accountId, ContactQuotaGuard.Channel channel, boolean statutory, @Nullable String memo);

	/** 지금 추심 연락을 막는 사유(소멸시효 완성·연락 유예). 예약 문자를 보내기 직전에 다시 본다 */
	Optional<String> blockingReason(long accountId, LocalDate date);

	/** 추심 중지(사망·회생·파산·공적 채무조정, R45) 중이면 그 사유 */
	Optional<String> collectionStopReason(long accountId);

	/** 그날 남은 추심 연락 가능 횟수 */
	int remaining(long accountId, LocalDate date);

	record ContactEntry(long id, LocalDate contactDate, OffsetDateTime contactedAt, String channel, String result,
			@Nullable String reason, @Nullable String memo, String createdBy) {
	}

	record AutoMessageEntry(long id, String type, String body, LocalDate scheduledDate, String status,
			@Nullable String skipReason, @Nullable OffsetDateTime sentAt, @Nullable Long contactLogId) {
	}

	/** 계약의 연락 기록 (최근 순) */
	List<ContactEntry> contactLog(long accountId);

	/** 계약의 자동 문자 (최근 순) */
	List<AutoMessageEntry> autoMessages(long accountId);
}
