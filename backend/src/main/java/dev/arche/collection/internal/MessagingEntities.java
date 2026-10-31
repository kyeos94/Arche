package dev.arche.collection.internal;

import dev.arche.common.jpa.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 연락 유예 (정책서 6장): 고객 요청(입원·재난 등) 시 최대 3개월, 1회 연장. 유예 중에는 추심 연락을 차단한다.
 */
@Entity
@Table(name = "contact_deferral")
class ContactDeferral extends BaseEntity {

	enum Status {
		ACTIVE, ENDED
	}

	static final int MAX_MONTHS = 3;

	private long accountId;
	private LocalDate startDate;
	private LocalDate endDate;
	private LocalDate originalEnd;
	private boolean extended;
	private String reason;
	@Enumerated(EnumType.STRING)
	private Status status;
	private LocalDate endedOn;

	protected ContactDeferral() {
	}

	ContactDeferral(long accountId, LocalDate startDate, LocalDate endDate, String reason) {
		this.accountId = accountId;
		this.startDate = startDate;
		this.endDate = endDate;
		this.originalEnd = endDate;
		this.reason = reason;
		this.status = Status.ACTIVE;
	}

	void extend(LocalDate newEnd) {
		this.endDate = newEnd;
		this.extended = true;
	}

	/** 유예를 앞당겨 끝냄 (고객 요청 철회 등). 그때부터 연락 가능 */
	void end(LocalDate on) {
		this.status = Status.ENDED;
		this.endedOn = on;
	}

	boolean coversDate(LocalDate d) {
		return status == Status.ACTIVE && !d.isBefore(startDate) && !d.isAfter(endDate);
	}

	long accountId() {
		return accountId;
	}

	LocalDate startDate() {
		return startDate;
	}

	LocalDate endDate() {
		return endDate;
	}

	LocalDate originalEnd() {
		return originalEnd;
	}

	boolean extended() {
		return extended;
	}

	String reason() {
		return reason;
	}

	Status status() {
		return status;
	}

	LocalDate endedOn() {
		return endedOn;
	}
}

/**
 * 자동 문자 (정책서 6장). 일마감이 다음 날 08:00 발송분을 만들고, 발송 시점에 한도·유예·시효·약속을 다시 판정한다.
 */
@Entity
@Table(name = "outbound_message")
class OutboundMessage extends BaseEntity {

	enum Type {
		/** 약정일 3일 전 납입 안내 (연체 전, 한도 제외) */
		PAYMENT_REMINDER(false),
		/** 연체 1·7·30일째 연체 안내 (추심 연락, 한도에 셈) */
		DELINQUENCY_1(true), DELINQUENCY_7(true), DELINQUENCY_30(true),
		/** 완제 안내 (한도 제외) */
		PAYOFF(false),
		/** 분할납부 약속일 3일 전 납부 안내 (약속 이행 중 허용되는 안내, 한도 제외) */
		PROMISE_REMINDER(false);

		final boolean collection;

		Type(boolean collection) {
			this.collection = collection;
		}
	}

	enum Status {
		SCHEDULED, SENT, SKIPPED
	}

	private long accountId;
	private long customerId;
	@Enumerated(EnumType.STRING)
	private Type type;
	private String dedupeKey;
	private String body;
	private LocalDate scheduledDate;
	@Enumerated(EnumType.STRING)
	private Status status;
	private String skipReason;
	private OffsetDateTime sentAt;
	private String providerId;
	private Long contactLogId;

	protected OutboundMessage() {
	}

	OutboundMessage(long accountId, long customerId, Type type, String dedupeKey, String body,
			LocalDate scheduledDate) {
		this.accountId = accountId;
		this.customerId = customerId;
		this.type = type;
		this.dedupeKey = dedupeKey;
		this.body = body;
		this.scheduledDate = scheduledDate;
		this.status = Status.SCHEDULED;
	}

	void sent(OffsetDateTime at, String providerId, Long contactLogId) {
		this.status = Status.SENT;
		this.sentAt = at;
		this.providerId = providerId;
		this.contactLogId = contactLogId;
	}

	void skip(String reason) {
		this.status = Status.SKIPPED;
		this.skipReason = reason;
	}

	long accountId() {
		return accountId;
	}

	long customerId() {
		return customerId;
	}

	Type type() {
		return type;
	}

	String body() {
		return body;
	}

	LocalDate scheduledDate() {
		return scheduledDate;
	}

	Status status() {
		return status;
	}

	String skipReason() {
		return skipReason;
	}

	OffsetDateTime sentAt() {
		return sentAt;
	}
}
