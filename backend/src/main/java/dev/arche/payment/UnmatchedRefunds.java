package dev.arche.payment;

import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 미확인 입금 반환 출금용 공개 API (guide 6.4, R62). 반환 출금을 작성하면 미확인 입금을 REFUNDED로 잡아 두어
 * 그 사이 계약에 매칭되지 않게 하고, 반려·취소·송금 실패면 OPEN으로 되돌린다. 원장에는 영향이 없다(미확인 입금은 계약 밖).
 */
public interface UnmatchedRefunds {

	record UnmatchedInfo(long unmatchedId, long paymentId, long amount, LocalDate bankDate,
			@Nullable String depositorName, String status) {
	}

	UnmatchedInfo get(long unmatchedId);

	/** 반환 가능한(OPEN) 미확인 입금 */
	List<UnmatchedInfo> open();

	/** OPEN → REFUNDED. OPEN이 아니면 UNMATCHED_CLOSED 예외 */
	void reserve(long unmatchedId);

	/** REFUNDED → OPEN */
	void release(long unmatchedId);
}
