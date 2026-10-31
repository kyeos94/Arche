package dev.arche.collection.internal;

import dev.arche.collection.ContactQuotaGuard;
import dev.arche.common.Money;
import dev.arche.common.jpa.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import org.hibernate.annotations.Immutable;

/** 추심 연락 기록 (G14). 차단된 시도도 남기지만 한도에는 세지 않는다 */
@Entity
@Immutable
@Table(name = "contact_log")
class ContactLog {

	enum Result {
		ALLOWED,
		/** 연락 가능 시간 밖의 문자: 다음 08:00으로 예약 (정책서 6장) */
		SCHEDULED,
		BLOCKED
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	private long accountId;
	private LocalDate contactDate;
	private OffsetDateTime contactedAt;
	@Enumerated(EnumType.STRING)
	private ContactQuotaGuard.Channel channel;
	@Enumerated(EnumType.STRING)
	private Result result;
	private int countInWindow;
	private String blockReason;
	private String memo;
	private Long staffId;
	private OffsetDateTime createdAt;
	private String createdBy;

	protected ContactLog() {
	}

	ContactLog(long accountId, LocalDate contactDate, OffsetDateTime contactedAt, ContactQuotaGuard.Channel channel,
			Result result, int countInWindow, String blockReason, String memo, Long staffId, String actor) {
		this.accountId = accountId;
		this.contactDate = contactDate;
		this.contactedAt = contactedAt;
		this.channel = channel;
		this.result = result;
		this.countInWindow = countInWindow;
		this.blockReason = blockReason;
		this.memo = memo;
		this.staffId = staffId;
		this.createdAt = contactedAt;
		this.createdBy = actor;
	}

	/** 한도에 세는 연락: 허용·예약된 일반 추심 연락 */
	boolean countsTowardQuota() {
		return result != Result.BLOCKED && channel != ContactQuotaGuard.Channel.STATUTORY_NOTICE;
	}

	Long getId() {
		return id;
	}

	LocalDate contactDate() {
		return contactDate;
	}

	OffsetDateTime contactedAt() {
		return contactedAt;
	}

	ContactQuotaGuard.Channel channel() {
		return channel;
	}

	Result result() {
		return result;
	}

	int countInWindow() {
		return countInWindow;
	}

	String blockReason() {
		return blockReason;
	}

	String memo() {
		return memo;
	}

	String createdBy() {
		return createdBy;
	}
}

/** 매입채권 분할납부 약속 "매월 n일에 m원" (정책서 1-1장, R18) */
@Entity
@Table(name = "payment_promise")
class PaymentPromise extends BaseEntity {

	enum Status {
		ACTIVE, TERMINATED, COMPLETED, CANCELLED
	}

	private long accountId;
	private LocalDate registeredOn;
	/** 첫 약속 월 (그 달 1일로 저장) */
	private LocalDate firstMonth;
	private int promiseDay;
	private long amount;
	private int months;
	@Enumerated(EnumType.STRING)
	private Status status;
	private int judgedCount;
	private LocalDate terminatedOn;
	private boolean legalReview;
	private String memo;

	protected PaymentPromise() {
	}

	PaymentPromise(long accountId, LocalDate registeredOn, YearMonth firstMonth, int promiseDay, Money amount,
			int months, String memo) {
		this.accountId = accountId;
		this.registeredOn = registeredOn;
		this.firstMonth = firstMonth.atDay(1);
		this.promiseDay = promiseDay;
		this.amount = amount.won();
		this.months = months;
		this.memo = memo;
		this.status = Status.ACTIVE;
	}

	/** 3회 연속 불이행: 약속 해제 + 법조치 검토 대상 (R18) */
	void terminate(LocalDate on) {
		status = Status.TERMINATED;
		terminatedOn = on;
		legalReview = true;
	}

	void complete() {
		status = Status.COMPLETED;
	}

	void judged(int count) {
		judgedCount = count;
	}

	long accountId() {
		return accountId;
	}

	LocalDate registeredOn() {
		return registeredOn;
	}

	YearMonth firstMonth() {
		return YearMonth.from(firstMonth);
	}

	int promiseDay() {
		return promiseDay;
	}

	Money amount() {
		return Money.won(amount);
	}

	int months() {
		return months;
	}

	Status status() {
		return status;
	}

	int judgedCount() {
		return judgedCount;
	}

	LocalDate terminatedOn() {
		return terminatedOn;
	}

	boolean legalReview() {
		return legalReview;
	}

	String memo() {
		return memo;
	}
}

/** 담당자 알림. dedupe_key로 같은 사건은 한 번만 만든다 */
@Entity
@Table(name = "collection_alert")
class CollectionAlert {

	enum Type {
		LIMITATION_IMMINENT, LIMITATION_COMPLETED, PROMISE_MISSED, PROMISE_TERMINATED
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	private long accountId;
	private Long staffId;
	@Enumerated(EnumType.STRING)
	private Type type;
	private String dedupeKey;
	private String message;
	private LocalDate alertDate;
	private OffsetDateTime readAt;
	private OffsetDateTime createdAt;

	protected CollectionAlert() {
	}

	CollectionAlert(long accountId, Long staffId, Type type, String dedupeKey, String message, LocalDate alertDate,
			OffsetDateTime now) {
		this.accountId = accountId;
		this.staffId = staffId;
		this.type = type;
		this.dedupeKey = dedupeKey;
		this.message = message;
		this.alertDate = alertDate;
		this.createdAt = now;
	}

	void read(OffsetDateTime now) {
		if (readAt == null) {
			readAt = now;
		}
	}

	Long getId() {
		return id;
	}

	long accountId() {
		return accountId;
	}

	Long staffId() {
		return staffId;
	}

	Type type() {
		return type;
	}

	String message() {
		return message;
	}

	LocalDate alertDate() {
		return alertDate;
	}

	OffsetDateTime readAt() {
		return readAt;
	}
}
