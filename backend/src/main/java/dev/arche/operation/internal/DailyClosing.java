package dev.arche.operation.internal;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.hibernate.annotations.Immutable;

/**
 * 일마감 결과(일보 요약). 마감된 날의 결과는 바꾸지 않고, 이후 정정은 처리일 일보의 "정정" 항목으로 표시한다 (R28).
 */
@Entity
@Immutable
@Table(name = "daily_closing")
class DailyClosing {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	private LocalDate businessDate;
	private OffsetDateTime closedAt;
	private String closedBy;
	private long paymentTotal;
	private int paymentCount;
	private long correctionTotal;
	private long unmatchedTotal;
	private int accountsJudged;
	private int newNotices;
	private int accelerations;
	private int mismatches;

	protected DailyClosing() {
	}

	DailyClosing(LocalDate businessDate, OffsetDateTime closedAt, String closedBy, long paymentTotal,
			int paymentCount, long correctionTotal, long unmatchedTotal, int accountsJudged, int newNotices,
			int accelerations, int mismatches) {
		this.businessDate = businessDate;
		this.closedAt = closedAt;
		this.closedBy = closedBy;
		this.paymentTotal = paymentTotal;
		this.paymentCount = paymentCount;
		this.correctionTotal = correctionTotal;
		this.unmatchedTotal = unmatchedTotal;
		this.accountsJudged = accountsJudged;
		this.newNotices = newNotices;
		this.accelerations = accelerations;
		this.mismatches = mismatches;
	}

	LocalDate businessDate() {
		return businessDate;
	}

	OffsetDateTime closedAt() {
		return closedAt;
	}

	String closedBy() {
		return closedBy;
	}

	long paymentTotal() {
		return paymentTotal;
	}

	int paymentCount() {
		return paymentCount;
	}

	long correctionTotal() {
		return correctionTotal;
	}

	long unmatchedTotal() {
		return unmatchedTotal;
	}

	int accountsJudged() {
		return accountsJudged;
	}

	int newNotices() {
		return newNotices;
	}

	int accelerations() {
		return accelerations;
	}

	int mismatches() {
		return mismatches;
	}
}
