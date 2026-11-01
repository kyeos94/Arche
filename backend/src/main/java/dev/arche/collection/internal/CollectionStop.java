package dev.arche.collection.internal;

import dev.arche.common.jpa.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 추심 중지 (정책서 R45·R46). 사유는 채무자 단위이며 계약마다 한 행, 같은 groupNo로 묶는다.
 * 중지 중에는 추심 연락과 자동 문자(완제 안내 제외)를 하지 않는다.
 */
@Entity
@Table(name = "collection_stop")
class CollectionStop extends BaseEntity {

	enum Reason {
		/** 사망(상속): 상속인 확인 전까지 중지. 금리·이자 변경 없음 */
		DECEASED("사망(상속)", false),
		/** 개인회생: 개시결정·금지명령 등 법원 통지일부터 이자 부과 중단 (R46) */
		REHABILITATION("개인회생", true),
		/** 파산: 선고일부터 이자 부과 중단 (R46) */
		BANKRUPTCY("파산", true),
		/** 공적 채무조정(가상): 신청 접수 시 중지, 확정 시 조정 금리 (R46) */
		PUBLIC_WORKOUT("공적 채무조정(가상)", false);

		final String label;
		final boolean suspendsInterest;

		Reason(String label, boolean suspendsInterest) {
			this.label = label;
			this.suspendsInterest = suspendsInterest;
		}
	}

	enum Status {
		ACTIVE, RELEASED
	}

	private String groupNo;
	private long accountId;
	private long customerId;
	@Enumerated(EnumType.STRING)
	private Reason reason;
	private LocalDate effectiveDate;
	private String memo;
	@Enumerated(EnumType.STRING)
	private Status status;
	private LocalDate releasedOn;
	private String releaseReason;
	private Long interestEventId;
	private BigDecimal reliefRate;
	private LocalDate reliefFrom;

	protected CollectionStop() {
	}

	CollectionStop(String groupNo, long accountId, long customerId, Reason reason, LocalDate effectiveDate,
			String memo) {
		this.groupNo = groupNo;
		this.accountId = accountId;
		this.customerId = customerId;
		this.reason = reason;
		this.effectiveDate = effectiveDate;
		this.memo = memo;
		this.status = Status.ACTIVE;
	}

	void interestSuspended(long eventId) {
		this.interestEventId = eventId;
	}

	void relief(BigDecimal rate, LocalDate from) {
		this.reliefRate = rate;
		this.reliefFrom = from;
	}

	void release(LocalDate on, String why) {
		this.status = Status.RELEASED;
		this.releasedOn = on;
		this.releaseReason = why;
	}

	boolean active() {
		return status == Status.ACTIVE;
	}

	String groupNo() {
		return groupNo;
	}

	long accountId() {
		return accountId;
	}

	long customerId() {
		return customerId;
	}

	Reason reason() {
		return reason;
	}

	LocalDate effectiveDate() {
		return effectiveDate;
	}

	String memo() {
		return memo;
	}

	Status status() {
		return status;
	}

	LocalDate releasedOn() {
		return releasedOn;
	}

	String releaseReason() {
		return releaseReason;
	}

	Long interestEventId() {
		return interestEventId;
	}

	BigDecimal reliefRate() {
		return reliefRate;
	}

	LocalDate reliefFrom() {
		return reliefFrom;
	}
}
