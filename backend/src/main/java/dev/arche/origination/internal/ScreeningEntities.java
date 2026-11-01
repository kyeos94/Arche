package dev.arche.origination.internal;

import dev.arche.common.BusinessException;
import dev.arche.common.jpa.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import org.hibernate.annotations.Immutable;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** 신청 단위 동의 이력 (guide 11.2). 신용조회 전에 유효한 동의가 있는지 검사한다 (R53) */
@Entity
@Table(name = "application_consent")
class ApplicationConsent extends BaseEntity {

	enum Type {
		CREDIT_INQUIRY
	}

	enum Channel {
		STAFF, BROKER, PLATFORM, LANDING
	}

	/** 동의 유효기간: 동의일(업무일자)부터 1년 (R53) */
	static final int VALID_YEARS = 1;

	private long applicationId;
	@Enumerated(EnumType.STRING)
	private Type consentType;
	@Enumerated(EnumType.STRING)
	private Channel channel;
	private LocalDate agreedOn;
	private LocalDate validUntil;
	private String evidence;
	private OffsetDateTime withdrawnAt;

	protected ApplicationConsent() {
	}

	ApplicationConsent(long applicationId, Type type, Channel channel, LocalDate agreedOn, String evidence) {
		this.applicationId = applicationId;
		this.consentType = type;
		this.channel = channel;
		this.agreedOn = agreedOn;
		this.validUntil = agreedOn.plusYears(VALID_YEARS).minusDays(1);
		this.evidence = evidence;
	}

	boolean validOn(LocalDate day) {
		return withdrawnAt == null && !day.isBefore(agreedOn) && !day.isAfter(validUntil);
	}

	void withdraw(OffsetDateTime at) {
		if (withdrawnAt != null) {
			throw new BusinessException("CONSENT_WITHDRAWN", "이미 철회된 동의입니다");
		}
		withdrawnAt = at;
	}

	String ref() {
		return "CONSENT-" + getId();
	}

	long applicationId() {
		return applicationId;
	}

	Type consentType() {
		return consentType;
	}

	Channel channel() {
		return channel;
	}

	LocalDate agreedOn() {
		return agreedOn;
	}

	LocalDate validUntil() {
		return validUntil;
	}

	String evidence() {
		return evidence;
	}

	OffsetDateTime withdrawnAt() {
		return withdrawnAt;
	}
}

/** 신용조회 결과 보관 (조회 기록 = 연계 이력). 수정하지 않는다 */
@Entity
@Immutable
@Table(name = "credit_inquiry")
@EntityListeners(AuditingEntityListener.class)
class CreditInquiry {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	private Long applicationId;
	private long customerId;
	private String consentRef;
	private String inquiryRef;
	private int grade;
	private boolean currentlyDelinquent;
	private int openLoanCount;
	private long totalDebt;
	private LocalDate inquiredOn;
	@CreatedDate
	private OffsetDateTime createdAt;
	@CreatedBy
	private String createdBy;

	protected CreditInquiry() {
	}

	CreditInquiry(Long applicationId, long customerId, String consentRef, String inquiryRef, int grade,
			boolean currentlyDelinquent, int openLoanCount, long totalDebt, LocalDate inquiredOn) {
		this.applicationId = applicationId;
		this.customerId = customerId;
		this.consentRef = consentRef;
		this.inquiryRef = inquiryRef;
		this.grade = grade;
		this.currentlyDelinquent = currentlyDelinquent;
		this.openLoanCount = openLoanCount;
		this.totalDebt = totalDebt;
		this.inquiredOn = inquiredOn;
	}

	Long getId() {
		return id;
	}

	Long applicationId() {
		return applicationId;
	}

	String consentRef() {
		return consentRef;
	}

	String inquiryRef() {
		return inquiryRef;
	}

	int grade() {
		return grade;
	}

	boolean currentlyDelinquent() {
		return currentlyDelinquent;
	}

	int openLoanCount() {
		return openLoanCount;
	}

	long totalDebt() {
		return totalDebt;
	}

	LocalDate inquiredOn() {
		return inquiredOn;
	}
}

/** 자동 거절 규칙별 판정 결과 (R53) */
@Entity
@Immutable
@Table(name = "screening_result")
@EntityListeners(AuditingEntityListener.class)
class ScreeningResult {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	private long applicationId;
	@Enumerated(EnumType.STRING)
	private ScreeningRules.RuleCode ruleCode;
	@Enumerated(EnumType.STRING)
	private ScreeningRules.Outcome outcome;
	private String detail;
	private LocalDate screenedOn;
	@CreatedDate
	private OffsetDateTime createdAt;
	@CreatedBy
	private String createdBy;

	protected ScreeningResult() {
	}

	ScreeningResult(long applicationId, ScreeningRules.Result r, LocalDate screenedOn) {
		this.applicationId = applicationId;
		this.ruleCode = r.rule();
		this.outcome = r.outcome();
		this.detail = r.detail().length() > 200 ? r.detail().substring(0, 200) : r.detail();
		this.screenedOn = screenedOn;
	}

	ScreeningRules.RuleCode ruleCode() {
		return ruleCode;
	}

	ScreeningRules.Outcome outcome() {
		return outcome;
	}

	String detail() {
		return detail;
	}

	LocalDate screenedOn() {
		return screenedOn;
	}
}

/** 상품별 징구 서류 목록 (R54). 참조 데이터 */
@Entity
@Immutable
@Table(name = "document_requirement")
class DocumentRequirement {

	@Id
	private Long id;
	private String productCode;
	@Enumerated(EnumType.STRING)
	private DocumentType docType;
	private boolean required;
	private int sortOrder;

	protected DocumentRequirement() {
	}

	String productCode() {
		return productCode;
	}

	DocumentType docType() {
		return docType;
	}

	boolean required() {
		return required;
	}

	int sortOrder() {
		return sortOrder;
	}
}

/**
 * 신청별 서류 징구 체크리스트 한 줄 (R54). 파일 본문은 StoragePort에 두고 여기에는 메타데이터만 둔다.
 * REQUESTED → RECEIVED(파일 업로드·서면 원본 접수) → VERIFIED(확인) 또는 REJECTED(보완 요청 → 다시 RECEIVED)
 */
@Entity
@Table(name = "application_document")
class ApplicationDocument extends BaseEntity {

	enum Status {
		REQUESTED, RECEIVED, VERIFIED, REJECTED
	}

	enum Via {
		UPLOAD, PAPER, LANDING, BROKER
	}

	private long applicationId;
	@Enumerated(EnumType.STRING)
	private DocumentType docType;
	private boolean required;
	@Enumerated(EnumType.STRING)
	private Status status;
	@Enumerated(EnumType.STRING)
	private Via receivedVia;
	private String storageKey;
	private String fileName;
	private String contentType;
	private Long sizeBytes;
	private String sha256;
	private OffsetDateTime receivedAt;
	private String reviewedBy;
	private OffsetDateTime reviewedAt;
	private String note;

	protected ApplicationDocument() {
	}

	ApplicationDocument(long applicationId, DocumentType docType, boolean required) {
		this.applicationId = applicationId;
		this.docType = docType;
		this.required = required;
		this.status = Status.REQUESTED;
	}

	void receiveFile(Via via, String storageKey, String fileName, String contentType, long size, String sha256,
			OffsetDateTime at) {
		requireReceivable();
		this.receivedVia = via;
		this.storageKey = storageKey;
		this.fileName = fileName;
		this.contentType = contentType;
		this.sizeBytes = size;
		this.sha256 = sha256;
		this.receivedAt = at;
		this.status = Status.RECEIVED;
		this.reviewedBy = null;
		this.reviewedAt = null;
	}

	void receivePaper(String note, OffsetDateTime at) {
		requireReceivable();
		this.receivedVia = Via.PAPER;
		this.storageKey = null;
		this.fileName = null;
		this.contentType = null;
		this.sizeBytes = null;
		this.sha256 = null;
		this.receivedAt = at;
		this.note = note;
		this.status = Status.RECEIVED;
	}

	void verify(String reviewer, OffsetDateTime at) {
		if (status != Status.RECEIVED) {
			throw new BusinessException("DOCUMENT_STATE", "접수된 서류만 확인할 수 있습니다 (현재 " + status + ")");
		}
		this.status = Status.VERIFIED;
		this.reviewedBy = reviewer;
		this.reviewedAt = at;
	}

	void reject(String reviewer, String note, OffsetDateTime at) {
		if (status != Status.RECEIVED) {
			throw new BusinessException("DOCUMENT_STATE", "접수된 서류만 보완 요청할 수 있습니다 (현재 " + status + ")");
		}
		if (note == null || note.isBlank()) {
			throw new BusinessException("REASON_REQUIRED", "보완 요청 사유를 입력하세요");
		}
		this.status = Status.REJECTED;
		this.reviewedBy = reviewer;
		this.reviewedAt = at;
		this.note = note;
	}

	private void requireReceivable() {
		if (status == Status.VERIFIED) {
			throw new BusinessException("DOCUMENT_STATE", "확인 완료된 서류는 바꿀 수 없습니다");
		}
	}

	long applicationId() {
		return applicationId;
	}

	DocumentType docType() {
		return docType;
	}

	boolean required() {
		return required;
	}

	Status status() {
		return status;
	}

	Via receivedVia() {
		return receivedVia;
	}

	String storageKey() {
		return storageKey;
	}

	String fileName() {
		return fileName;
	}

	String contentType() {
		return contentType;
	}

	Long sizeBytes() {
		return sizeBytes;
	}

	String sha256() {
		return sha256;
	}

	OffsetDateTime receivedAt() {
		return receivedAt;
	}

	String reviewedBy() {
		return reviewedBy;
	}

	OffsetDateTime reviewedAt() {
		return reviewedAt;
	}

	String note() {
		return note;
	}
}

/** 심사자 프로필: 담당 상품, 부재 여부 (guide 2.3, R55). 프로필이 없는 심사역은 전 상품 담당·배정 가능으로 본다 */
@Entity
@Table(name = "underwriter_profile")
@EntityListeners(AuditingEntityListener.class)
class UnderwriterProfile {

	@Id
	private Long staffId;
	private String productCodes;
	private boolean available;
	@CreatedDate
	private OffsetDateTime createdAt;
	@CreatedBy
	private String createdBy;
	@LastModifiedDate
	private OffsetDateTime updatedAt;
	@Version
	private Long version;

	protected UnderwriterProfile() {
	}

	UnderwriterProfile(long staffId, List<String> productCodes, boolean available) {
		this.staffId = staffId;
		update(productCodes, available);
	}

	void update(List<String> productCodes, boolean available) {
		this.productCodes = String.join(",", productCodes.stream().map(String::trim).filter(s -> !s.isEmpty())
				.distinct().sorted().toList());
		this.available = available;
	}

	boolean covers(String productCode) {
		return products().contains(productCode);
	}

	List<String> products() {
		return productCodes.isBlank() ? List.of() : Arrays.asList(productCodes.split(","));
	}

	long staffId() {
		return staffId;
	}

	boolean available() {
		return available;
	}
}
