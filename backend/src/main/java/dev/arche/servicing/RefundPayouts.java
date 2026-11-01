package dev.arche.servicing;

import dev.arche.common.Money;
import java.util.List;

/**
 * 완제 후 과입금 반환(정책서 4장 "반환 대기") 출금 예약 공개 API (R62).
 * 원장(엔진 재생 결과)은 건드리지 않고, 추가 전용 예약 기록(+ 예약, − 해제)으로 반환 가능액을 관리한다.
 * 반환 가능액 = 엔진이 계산한 반환 대기액 − 예약 합계. 모든 변경은 계약 단위 락 안에서 한다.
 */
public interface RefundPayouts {

	record Refundable(long accountId, String accountNo, long refundPending, long reserved, long available) {
	}

	/** 반환 가능액 */
	Refundable refundable(long accountId);

	/** 반환 대기가 남은 계약 (출금 작성 화면용) */
	List<Refundable> withRefundPending();

	/** 출금 작성 시 예약. 반환 가능액을 넘으면 REFUND_EXCEEDED 예외 */
	void reserve(long accountId, String withdrawalNo, Money amount);

	/** 반려·취소·송금 실패 시 예약 해제 (음수 기록) */
	void release(long accountId, String withdrawalNo, Money amount, String memo);
}
