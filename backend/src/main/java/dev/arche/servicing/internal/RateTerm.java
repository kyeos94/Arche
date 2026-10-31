package dev.arche.servicing.internal;

import dev.arche.common.InterestRate;
import dev.arche.common.jpa.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 금리 이력 (guide 5.1). 유효시간 [validFrom, validTo), 기록시간 recordedAt. 겹침은 DB 배타 제약으로 막는다.
 */
@Entity
@Table(name = "rate_term")
class RateTerm extends BaseEntity {

	private long accountId;
	private BigDecimal contractRate;
	private LocalDate validFrom;
	private LocalDate validTo;
	private String reason;
	private OffsetDateTime recordedAt;

	protected RateTerm() {
	}

	RateTerm(long accountId, InterestRate rate, LocalDate validFrom, String reason, OffsetDateTime recordedAt) {
		this.accountId = accountId;
		this.contractRate = rate.percent();
		this.validFrom = validFrom;
		this.reason = reason;
		this.recordedAt = recordedAt;
	}

	void closeAt(LocalDate to) {
		this.validTo = to;
	}

	InterestRate rate() {
		return new InterestRate(contractRate);
	}

	LocalDate validFrom() {
		return validFrom;
	}

	LocalDate validTo() {
		return validTo;
	}

	String reason() {
		return reason;
	}

	OffsetDateTime recordedAt() {
		return recordedAt;
	}
}
