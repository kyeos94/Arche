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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import org.hibernate.annotations.Immutable;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** 대출중개업자 (guide 2.1·2.3). API 키는 해시만 보관한다 */
@Entity
@Table(name = "broker")
class Broker extends BaseEntity {

	enum Status {
		ACTIVE, SUSPENDED
	}

	private String brokerNo;
	private String name;
	private String registrationNo;
	private String apiKeyHash;
	private String allowedIps;
	@Enumerated(EnumType.STRING)
	private Status status;

	protected Broker() {
	}

	Broker(String brokerNo, String name, String registrationNo, String apiKeyHash, String allowedIps) {
		this.brokerNo = brokerNo;
		this.name = name;
		this.registrationNo = registrationNo;
		this.apiKeyHash = apiKeyHash;
		this.allowedIps = blankToNull(allowedIps);
		this.status = Status.ACTIVE;
	}

	void rotateKey(String hash) {
		this.apiKeyHash = hash;
	}

	void changeStatus(Status s) {
		this.status = s;
	}

	void changeAllowedIps(String ips) {
		this.allowedIps = blankToNull(ips);
	}

	/** IP 허용 목록이 비어 있으면 제한 없음 */
	boolean allows(String ip) {
		return allowedIps == null || ipList().contains(ip);
	}

	List<String> ipList() {
		return allowedIps == null ? List.of() : Arrays.stream(allowedIps.split(",")).map(String::trim).toList();
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.replace(" ", "");
	}

	String brokerNo() {
		return brokerNo;
	}

	String name() {
		return name;
	}

	String registrationNo() {
		return registrationNo;
	}

	String allowedIps() {
		return allowedIps;
	}

	Status status() {
		return status;
	}
}

/** 중개수수료율 이력 [validFrom, validTo) (R56) */
@Entity
@Table(name = "broker_commission_rate")
class BrokerCommissionRate extends BaseEntity {

	private long brokerId;
	private BigDecimal rate;
	private LocalDate validFrom;
	private LocalDate validTo;
	private String reason;

	protected BrokerCommissionRate() {
	}

	BrokerCommissionRate(long brokerId, BigDecimal rate, LocalDate validFrom, String reason) {
		this.brokerId = brokerId;
		this.rate = rate;
		this.validFrom = validFrom;
		this.reason = reason;
	}

	void closeAt(LocalDate to) {
		this.validTo = to;
	}

	boolean coversDay(LocalDate day) {
		return !day.isBefore(validFrom) && (validTo == null || day.isBefore(validTo));
	}

	long brokerId() {
		return brokerId;
	}

	BigDecimal rate() {
		return rate;
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
}

/** 중개수수료 정산·환수 (추가 전용, R56) */
@Entity
@Immutable
@Table(name = "broker_commission")
@EntityListeners(AuditingEntityListener.class)
class BrokerCommission {

	enum EntryType {
		SETTLEMENT, CLAWBACK
	}

	enum ReasonCode {
		EARLY_PAYOFF, MIS_SELLING
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	private long brokerId;
	private long applicationId;
	@Enumerated(EnumType.STRING)
	private EntryType entryType;
	private long baseAmount;
	private BigDecimal rate;
	private long amount;
	private LocalDate occurredOn;
	@Enumerated(EnumType.STRING)
	private ReasonCode reasonCode;
	private String reason;
	@CreatedDate
	private OffsetDateTime createdAt;
	@CreatedBy
	private String createdBy;

	protected BrokerCommission() {
	}

	BrokerCommission(long brokerId, long applicationId, EntryType type, long baseAmount, BigDecimal rate, long amount,
			LocalDate occurredOn, ReasonCode reasonCode, String reason) {
		this.brokerId = brokerId;
		this.applicationId = applicationId;
		this.entryType = type;
		this.baseAmount = baseAmount;
		this.rate = rate;
		this.amount = amount;
		this.occurredOn = occurredOn;
		this.reasonCode = reasonCode;
		this.reason = reason;
	}

	Long getId() {
		return id;
	}

	long brokerId() {
		return brokerId;
	}

	long applicationId() {
		return applicationId;
	}

	EntryType entryType() {
		return entryType;
	}

	long baseAmount() {
		return baseAmount;
	}

	BigDecimal rate() {
		return rate;
	}

	long amount() {
		return amount;
	}

	LocalDate occurredOn() {
		return occurredOn;
	}

	ReasonCode reasonCode() {
		return reasonCode;
	}

	String reason() {
		return reason;
	}

	OffsetDateTime createdAt() {
		return createdAt;
	}
}

/** 플랫폼 1단계 한도조회 결과 (R57). 식별번호는 블라인드 인덱스만 */
@Entity
@Table(name = "platform_inquiry")
class PlatformInquiry extends BaseEntity {

	private String inquiryNo;
	private String platformCode;
	private String platformRef;
	private String residentNoBidx;
	private String productCode;
	private long requestedAmount;
	private int termMonths;
	private String consentRef;
	private String creditInquiryRef;
	private Integer creditGrade;
	private boolean offered;
	private Long offeredLimit;
	private BigDecimal offeredRate;
	private String declineReason;
	private LocalDate inquiredOn;
	private LocalDate validUntil;
	private Long applicationId;

	protected PlatformInquiry() {
	}

	PlatformInquiry(String inquiryNo, String platformCode, String platformRef, String residentNoBidx,
			String productCode, long requestedAmount, int termMonths, String consentRef, String creditInquiryRef,
			Integer creditGrade, PlatformOffer.Offer offer, LocalDate inquiredOn) {
		this.inquiryNo = inquiryNo;
		this.platformCode = platformCode;
		this.platformRef = platformRef;
		this.residentNoBidx = residentNoBidx;
		this.productCode = productCode;
		this.requestedAmount = requestedAmount;
		this.termMonths = termMonths;
		this.consentRef = consentRef;
		this.creditInquiryRef = creditInquiryRef;
		this.creditGrade = creditGrade;
		this.offered = offer.offered();
		this.offeredLimit = offer.limit() == null ? null : offer.limit().won();
		this.offeredRate = offer.rate() == null ? null : offer.rate().percent();
		this.declineReason = offer.declineReason();
		this.inquiredOn = inquiredOn;
		this.validUntil = inquiredOn.plusDays(PlatformOffer.VALID_DAYS - 1L);
	}

	void linkApplication(long applicationId) {
		if (this.applicationId != null && this.applicationId != applicationId) {
			throw new BusinessException("DUPLICATE", "이미 신청한 한도조회입니다");
		}
		this.applicationId = applicationId;
	}

	String inquiryNo() {
		return inquiryNo;
	}

	String platformCode() {
		return platformCode;
	}

	String platformRef() {
		return platformRef;
	}

	String residentNoBidx() {
		return residentNoBidx;
	}

	String productCode() {
		return productCode;
	}

	long requestedAmount() {
		return requestedAmount;
	}

	int termMonths() {
		return termMonths;
	}

	String consentRef() {
		return consentRef;
	}

	Integer creditGrade() {
		return creditGrade;
	}

	boolean offered() {
		return offered;
	}

	Long offeredLimit() {
		return offeredLimit;
	}

	BigDecimal offeredRate() {
		return offeredRate;
	}

	String declineReason() {
		return declineReason;
	}

	LocalDate inquiredOn() {
		return inquiredOn;
	}

	LocalDate validUntil() {
		return validUntil;
	}

	Long applicationId() {
		return applicationId;
	}
}

/** 플랫폼 3단계 결과 통보 이력 (결과별 1행) */
@Entity
@Table(name = "platform_result_notice")
class PlatformResultNotice extends BaseEntity {

	private long applicationId;
	private String platformCode;
	private String result;
	private boolean delivered;
	private String receiptId;
	private int attempts;
	private OffsetDateTime lastSentAt;

	protected PlatformResultNotice() {
	}

	PlatformResultNotice(long applicationId, String platformCode, String result) {
		this.applicationId = applicationId;
		this.platformCode = platformCode;
		this.result = result;
	}

	void attempted(boolean ok, String receiptId, OffsetDateTime at) {
		this.attempts++;
		this.delivered = ok;
		this.receiptId = receiptId;
		this.lastSentAt = at;
	}

	long applicationId() {
		return applicationId;
	}

	String platformCode() {
		return platformCode;
	}

	String result() {
		return result;
	}

	boolean delivered() {
		return delivered;
	}

	String receiptId() {
		return receiptId;
	}

	int attempts() {
		return attempts;
	}

	OffsetDateTime lastSentAt() {
		return lastSentAt;
	}
}

/**
 * 본인인증 랜딩 링크 (guide 11.3, R58). 토큰 원문은 저장하지 않고 SHA-256만 둔다.
 * ISSUED → VERIFIED(본인 확인) → COMPLETED(제출 완료). 불일치 3회면 FAILED, 재발급하면 이전 링크는 REVOKED
 */
@Entity
@Table(name = "identity_verification")
class IdentityVerification extends BaseEntity {

	enum Status {
		ISSUED, VERIFIED, COMPLETED, FAILED, REVOKED
	}

	static final int MAX_FAILURES = 3;

	private long applicationId;
	private long customerId;
	private String tokenHash;
	@Enumerated(EnumType.STRING)
	private Status status;
	private OffsetDateTime expiresAt;
	private int failedAttempts;
	private String connectingInfoHash;
	private OffsetDateTime verifiedAt;
	private OffsetDateTime completedAt;

	protected IdentityVerification() {
	}

	IdentityVerification(long applicationId, long customerId, String tokenHash, OffsetDateTime expiresAt) {
		this.applicationId = applicationId;
		this.customerId = customerId;
		this.tokenHash = tokenHash;
		this.expiresAt = expiresAt;
		this.status = Status.ISSUED;
	}

	boolean expired(OffsetDateTime now) {
		return !now.isBefore(expiresAt);
	}

	/** 링크를 쓸 수 있는 상태인지 (만료·완료·실패·무효화가 아님) */
	void requireUsable(OffsetDateTime now) {
		if (status == Status.COMPLETED || status == Status.FAILED || status == Status.REVOKED || expired(now)) {
			throw new BusinessException("LANDING_EXPIRED", "링크가 만료되었거나 이미 사용되었습니다. 담당자에게 다시 요청하세요");
		}
	}

	void requireVerified(OffsetDateTime now) {
		requireUsable(now);
		if (status != Status.VERIFIED) {
			throw new BusinessException("IDENTITY_REQUIRED", "본인인증을 먼저 해주세요");
		}
	}

	/** @return 실패 후 남은 시도 횟수 */
	int failed() {
		failedAttempts++;
		if (failedAttempts >= MAX_FAILURES) {
			status = Status.FAILED;
		}
		return Math.max(0, MAX_FAILURES - failedAttempts);
	}

	void verified(String ciHash, OffsetDateTime at) {
		this.status = Status.VERIFIED;
		this.connectingInfoHash = ciHash;
		this.verifiedAt = at;
	}

	void complete(OffsetDateTime at) {
		this.status = Status.COMPLETED;
		this.completedAt = at;
	}

	void revoke() {
		if (status == Status.ISSUED || status == Status.VERIFIED) {
			status = Status.REVOKED;
		}
	}

	long applicationId() {
		return applicationId;
	}

	long customerId() {
		return customerId;
	}

	Status status() {
		return status;
	}

	OffsetDateTime expiresAt() {
		return expiresAt;
	}

	int failedAttempts() {
		return failedAttempts;
	}

	OffsetDateTime verifiedAt() {
		return verifiedAt;
	}

	OffsetDateTime completedAt() {
		return completedAt;
	}
}
