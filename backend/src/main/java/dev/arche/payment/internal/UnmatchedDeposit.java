package dev.arche.payment.internal;

import dev.arche.common.jpa.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 미확인 입금 (guide 6.3). 잔액은 부채(가수금)이므로 일마감에서 집계한다.
 */
@Entity
@Table(name = "unmatched_deposit")
class UnmatchedDeposit extends BaseEntity {

	enum Status {
		OPEN, MATCHED, SPLIT, REFUNDED
	}

	private long paymentId;
	@Enumerated(EnumType.STRING)
	private Status status;
	private Long matchedAccountId;
	private OffsetDateTime matchedAt;
	private Long matchedBy;
	private Long refundPaymentId;

	protected UnmatchedDeposit() {
	}

	UnmatchedDeposit(long paymentId) {
		this.paymentId = paymentId;
		this.status = Status.OPEN;
	}

	void match(long accountId, long staffId, OffsetDateTime now) {
		this.status = Status.MATCHED;
		this.matchedAccountId = accountId;
		this.matchedBy = staffId;
		this.matchedAt = now;
	}

	long paymentId() {
		return paymentId;
	}

	Status status() {
		return status;
	}

	Long matchedAccountId() {
		return matchedAccountId;
	}

	OffsetDateTime matchedAt() {
		return matchedAt;
	}
}
