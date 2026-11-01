package dev.arche.communication.internal;

import dev.arche.common.crypto.EncryptedStringConverter;
import dev.arche.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.hibernate.annotations.Immutable;

/** 발송 수단 */
enum DispatchChannel {
	SMS, FAX, POST
}

/** 발송 목적 (R59): 추심 안내는 7일 7회·연락 유예 대상, 법정·계약상 통지는 제외 */
enum DispatchPurpose {
	COLLECTION, NOTICE
}

/** 문자·팩스·우편 템플릿 (guide 13.3 문자 템플릿). 목적은 템플릿이 정하고 발송자가 바꿀 수 없다 (R59) */
@Entity
@Table(name = "message_template")
class MessageTemplate extends BaseEntity {

	private String code;
	private String name;
	@Enumerated(EnumType.STRING)
	private DispatchChannel channel;
	@Enumerated(EnumType.STRING)
	private DispatchPurpose purpose;
	private String title;
	private String body;
	private boolean active;

	protected MessageTemplate() {
	}

	MessageTemplate(String code, String name, DispatchChannel channel, DispatchPurpose purpose, String title,
			String body) {
		this.code = code;
		this.active = true;
		update(name, channel, purpose, title, body, true);
	}

	void update(String name, DispatchChannel channel, DispatchPurpose purpose, String title, String body,
			boolean active) {
		this.name = name;
		this.channel = channel;
		this.purpose = purpose;
		this.title = title;
		this.body = body;
		this.active = active;
	}

	String code() {
		return code;
	}

	String name() {
		return name;
	}

	DispatchChannel channel() {
		return channel;
	}

	DispatchPurpose purpose() {
		return purpose;
	}

	String title() {
		return title;
	}

	String body() {
		return body;
	}

	boolean active() {
		return active;
	}
}

/**
 * 수동 발송 1건 (문자·팩스·우편). 판정 결과(허용·예약·차단)와 기관 처리 결과(발송·실패·배달)를 함께 둔다.
 * 팩스 번호는 암호문으로 저장한다.
 */
@Entity
@Table(name = "communication_dispatch")
class CommunicationDispatch extends BaseEntity {

	enum Status {
		/** 연락 가능 시간 밖 문자: contactDate 08:00에 발송 */
		SCHEDULED,
		/** 문자·팩스 발송 완료 */
		SENT,
		/** 우편 접수 */
		ACCEPTED, IN_TRANSIT, DELIVERED, RETURNED,
		/** 기관 처리 실패 (팩스 응답 없음 등) */
		FAILED,
		/** 7일 7회·연락 유예·소멸시효·연락 가능 시간으로 차단 */
		BLOCKED
	}

	private long accountId;
	private long customerId;
	@Enumerated(EnumType.STRING)
	private DispatchChannel channel;
	@Enumerated(EnumType.STRING)
	private DispatchPurpose purpose;
	private Long templateId;
	private String title;
	private String body;
	@Convert(converter = EncryptedStringConverter.class)
	@Column(name = "recipient_enc")
	private String recipient;
	@Enumerated(EnumType.STRING)
	private Status status;
	private String blockReason;
	private LocalDate contactDate;
	private int countInWindow;
	private Long contactLogId;
	private String providerId;
	private String fileRef;
	private OffsetDateTime sentAt;
	private LocalDate deliveredOn;
	private Long staffId;

	protected CommunicationDispatch() {
	}

	CommunicationDispatch(long accountId, long customerId, DispatchChannel channel, DispatchPurpose purpose,
			Long templateId, String title, String body, String recipient, LocalDate contactDate, Long staffId) {
		this.accountId = accountId;
		this.customerId = customerId;
		this.channel = channel;
		this.purpose = purpose;
		this.templateId = templateId;
		this.title = title;
		this.body = body;
		this.recipient = recipient;
		this.contactDate = contactDate;
		this.staffId = staffId;
		this.status = Status.SCHEDULED;
	}

	void judged(Status status, LocalDate contactDate, int countInWindow, String reason, Long contactLogId) {
		this.status = status;
		this.contactDate = contactDate;
		this.countInWindow = countInWindow;
		this.blockReason = reason;
		this.contactLogId = contactLogId;
	}

	void sent(Status status, String providerId, String fileRef, OffsetDateTime at, String failure) {
		this.status = status;
		this.providerId = providerId;
		this.fileRef = fileRef;
		this.sentAt = at;
		if (failure != null) {
			this.blockReason = failure;
		}
	}

	void block(String reason) {
		this.status = Status.BLOCKED;
		this.blockReason = reason;
	}

	void delivery(Status status, LocalDate deliveredOn) {
		this.status = status;
		this.deliveredOn = deliveredOn;
	}

	@Override
	public String toString() {
		return "CommunicationDispatch[" + getId() + ", " + channel + ", " + status + "]";
	}

	long accountId() {
		return accountId;
	}

	long customerId() {
		return customerId;
	}

	DispatchChannel channel() {
		return channel;
	}

	DispatchPurpose purpose() {
		return purpose;
	}

	Long templateId() {
		return templateId;
	}

	String title() {
		return title;
	}

	String body() {
		return body;
	}

	String recipient() {
		return recipient;
	}

	Status status() {
		return status;
	}

	String blockReason() {
		return blockReason;
	}

	LocalDate contactDate() {
		return contactDate;
	}

	int countInWindow() {
		return countInWindow;
	}

	Long contactLogId() {
		return contactLogId;
	}

	String providerId() {
		return providerId;
	}

	String fileRef() {
		return fileRef;
	}

	OffsetDateTime sentAt() {
		return sentAt;
	}

	LocalDate deliveredOn() {
		return deliveredOn;
	}
}

/** 상담 메모. 연락이 아니므로 7일 7회에 세지 않는다 (R59). 추가 전용 */
@Entity
@Immutable
@Table(name = "contact_memo")
class ContactMemo {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	private long accountId;
	private LocalDate memoDate;
	private String body;
	private Long staffId;
	private OffsetDateTime createdAt;
	private String createdBy;

	protected ContactMemo() {
	}

	ContactMemo(long accountId, LocalDate memoDate, String body, Long staffId, OffsetDateTime createdAt,
			String createdBy) {
		this.accountId = accountId;
		this.memoDate = memoDate;
		this.body = body;
		this.staffId = staffId;
		this.createdAt = createdAt;
		this.createdBy = createdBy;
	}

	Long getId() {
		return id;
	}

	LocalDate memoDate() {
		return memoDate;
	}

	String body() {
		return body;
	}

	OffsetDateTime createdAt() {
		return createdAt;
	}

	String createdBy() {
		return createdBy;
	}
}
