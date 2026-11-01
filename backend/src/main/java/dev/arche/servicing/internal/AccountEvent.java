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
		PAYMENT, PREPAYMENT_REQUEST, DUE_DAY_CHANGE, COST_CHARGE, WORKOUT,
		/** R39~R46 채권 생애주기 */
		CHARGE_OFF, SALE, REPURCHASE, BUYER_TRANSFER, ADJUSTMENT, SETOFF_OUT, SETOFF_IN, INTEREST_SUSPENSION
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
	private Long waiveLate;
	private Long waiveNormal;
	private Long waivePrincipal;
	private Integer installments;
	/** 채무자 승인이 아닌 입금 (보증인·제3자·법원 배당 등): 소멸시효 중단으로 세지 않음 (R50). 재생에는 영향 없음 */
	private boolean limitationExcluded;

	private Long costDelta;
	private Long preAcqDown;
	private LocalDate endDate;
	private String setoffSource;
	private Long linkedEventId;

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

	/** 채무조정 체결 (R9). newDueDay = 재약정 약정일, installments = 재약정 회차 수 */
	static AccountEvent workout(long accountId, LocalDate on, Money waiveLate, Money waiveNormal, Money waivePrincipal,
			int installments, int dueDay, String reason) {
		AccountEvent e = new AccountEvent(accountId, Type.WORKOUT, on, null, dueDay, null, reason);
		e.waiveLate = waiveLate.won();
		e.waiveNormal = waiveNormal.won();
		e.waivePrincipal = waivePrincipal.won();
		e.installments = installments;
		return e;
	}

	/** 상각 확정 (R39)·매각 (R40)·환매 (R41). 날짜만 있는 생애주기 이벤트 */
	static AccountEvent lifecycle(long accountId, Type type, LocalDate on, String reason) {
		return new AccountEvent(accountId, type, on, null, null, null, reason);
	}

	/** 양수인에게 이전 (R40) */
	static AccountEvent buyerTransfer(long accountId, LocalDate on, Money amount, String reason) {
		return new AccountEvent(accountId, Type.BUYER_TRANSFER, on, amount.won(), null, null, reason);
	}

	/** 수기 조정 (R42). 감액은 양수, costDelta는 증액이 양수 */
	static AccountEvent adjustment(long accountId, LocalDate on, Money principalDown, Money normalDown,
			Money lateDown, Money costDelta, Money preAcqDown, String reason) {
		AccountEvent e = new AccountEvent(accountId, Type.ADJUSTMENT, on, null, null, null, reason);
		e.waivePrincipal = principalDown.won();
		e.waiveNormal = normalDown.won();
		e.waiveLate = lateDown.won();
		e.costDelta = costDelta.won();
		e.preAcqDown = preAcqDown.won();
		return e;
	}

	/** 상계 원천 (R44) */
	static AccountEvent setoffOut(long accountId, LocalDate on, Money amount, LoanEvent.SetoffSource source,
			String reason) {
		AccountEvent e = new AccountEvent(accountId, Type.SETOFF_OUT, on, amount.won(), null, null, reason);
		e.setoffSource = source.name();
		return e;
	}

	/** 상계 대상 (R44) */
	static AccountEvent setoffIn(long accountId, LocalDate on, Money amount, long linkedEventId, String reason) {
		AccountEvent e = new AccountEvent(accountId, Type.SETOFF_IN, on, amount.won(), null, null, reason);
		e.linkedEventId = linkedEventId;
		return e;
	}

	/** 이자 부과 중단 [from, 계속) (R46) */
	static AccountEvent interestSuspension(long accountId, LocalDate from, String reason) {
		return new AccountEvent(accountId, Type.INTEREST_SUSPENSION, from, null, null, null, reason);
	}

	/** 이자 부과 중단을 끝냄 (end 당일부터 다시 이자). 시작일 이전이면 시작일로 (기간 0일) */
	void endSuspension(LocalDate end) {
		this.endDate = end.isBefore(eventDate) ? eventDate : end;
	}

	void link(long otherEventId) {
		this.linkedEventId = otherEventId;
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
			case WORKOUT -> new LoanEvent.Workout(ref(), eventDate, Money.won(waiveLate), Money.won(waiveNormal),
					Money.won(waivePrincipal), installments, newDueDay);
			case CHARGE_OFF -> new LoanEvent.ChargeOff(ref(), eventDate);
			case SALE -> new LoanEvent.Sale(ref(), eventDate);
			case REPURCHASE -> new LoanEvent.Repurchase(ref(), eventDate);
			case BUYER_TRANSFER -> new LoanEvent.BuyerTransfer(ref(), eventDate, Money.won(amount));
			case ADJUSTMENT -> new LoanEvent.Adjustment(ref(), eventDate, Money.won(waivePrincipal),
					Money.won(waiveNormal), Money.won(waiveLate), Money.won(costDelta), Money.won(preAcqDown));
			case SETOFF_OUT -> new LoanEvent.SetoffOut(ref(), eventDate, Money.won(amount),
					LoanEvent.SetoffSource.valueOf(setoffSource));
			case SETOFF_IN -> new LoanEvent.SetoffIn(ref(), eventDate, Money.won(amount));
			case INTEREST_SUSPENSION -> new LoanEvent.InterestSuspension(ref(), eventDate, endDate);
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

	void excludeFromLimitation() {
		this.limitationExcluded = true;
	}

	boolean limitationExcluded() {
		return limitationExcluded;
	}

	long accountId() {
		return accountId;
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

	Long waiveLate() {
		return waiveLate;
	}

	Long waiveNormal() {
		return waiveNormal;
	}

	Long waivePrincipal() {
		return waivePrincipal;
	}

	Integer installments() {
		return installments;
	}

	Long costDelta() {
		return costDelta;
	}

	Long preAcqDown() {
		return preAcqDown;
	}

	LocalDate endDate() {
		return endDate;
	}

	String setoffSource() {
		return setoffSource;
	}

	Long linkedEventId() {
		return linkedEventId;
	}
}
