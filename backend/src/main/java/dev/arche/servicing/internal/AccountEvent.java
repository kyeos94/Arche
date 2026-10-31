package dev.arche.servicing.internal;

import dev.arche.common.Money;
import dev.arche.common.jpa.BaseEntity;
import dev.arche.servicing.engine.LoanEvent;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * 재생 입력 이벤트. 엔진은 ACTIVE 이벤트만 재생하며, 취소는 상태만 바꾸고 행은 남긴다 (R26).
 * 원장 거래의 source_ref는 "EV-{id}" 형식으로 이 행을 가리킨다.
 */
@Entity
@Table(name = "account_event")
class AccountEvent extends BaseEntity {

	enum Type {
		PAYMENT, PREPAYMENT_REQUEST, DUE_DAY_CHANGE, COST_CHARGE
	}

	private long accountId;
	@Enumerated(EnumType.STRING)
	private Type type;
	private LocalDate eventDate;
	private Long amount;
	private Integer newDueDay;
	private Long paymentId;
	private String status;
	private LocalDate cancelledOn;
	private String reason;

	protected AccountEvent() {
	}

	private AccountEvent(long accountId, Type type, LocalDate eventDate, Long amount, Integer newDueDay,
			Long paymentId, String reason) {
		this.accountId = accountId;
		this.type = type;
		this.eventDate = eventDate;
		this.amount = amount;
		this.newDueDay = newDueDay;
		this.paymentId = paymentId;
		this.reason = reason;
		this.status = "ACTIVE";
	}

	static AccountEvent payment(long accountId, long paymentId, LocalDate bankDate, Money amount) {
		return new AccountEvent(accountId, Type.PAYMENT, bankDate, amount.won(), null, paymentId, null);
	}

	/** amount가 null이면 전액 중도상환 */
	static AccountEvent prepayment(long accountId, Long paymentId, LocalDate on, Money principal) {
		return new AccountEvent(accountId, Type.PREPAYMENT_REQUEST, on, principal == null ? null : principal.won(),
				null, paymentId, null);
	}

	static AccountEvent dueDayChange(long accountId, LocalDate requestedOn, int newDueDay, String reason) {
		return new AccountEvent(accountId, Type.DUE_DAY_CHANGE, requestedOn, null, newDueDay, null, reason);
	}

	static AccountEvent cost(long accountId, LocalDate on, Money amount, String reason) {
		return new AccountEvent(accountId, Type.COST_CHARGE, on, amount.won(), null, null, reason);
	}

	String ref() {
		return "EV-" + getId();
	}

	LoanEvent toEngine() {
		return switch (type) {
			case PAYMENT -> new LoanEvent.Payment(ref(), eventDate, Money.won(amount));
			case PREPAYMENT_REQUEST -> new LoanEvent.PrepaymentRequest(ref(), eventDate,
					amount == null ? null : Money.won(amount));
			case DUE_DAY_CHANGE -> new LoanEvent.DueDayChange(ref(), eventDate, newDueDay);
			case COST_CHARGE -> new LoanEvent.CostCharge(ref(), eventDate, Money.won(amount));
		};
	}

	void cancel(LocalDate on, String why) {
		this.status = "CANCELLED";
		this.cancelledOn = on;
		this.reason = why;
	}

	boolean active() {
		return "ACTIVE".equals(status);
	}

	Type type() {
		return type;
	}

	LocalDate eventDate() {
		return eventDate;
	}

	Long amount() {
		return amount;
	}

	Integer newDueDay() {
		return newDueDay;
	}

	Long paymentId() {
		return paymentId;
	}

	String status() {
		return status;
	}

	LocalDate cancelledOn() {
		return cancelledOn;
	}

	String reason() {
		return reason;
	}
}
