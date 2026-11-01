package dev.arche.origination.internal;

import dev.arche.common.BusinessException;
import dev.arche.common.jpa.BaseEntity;
import dev.arche.origination.ApplicationStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "loan_application")
class LoanApplication extends BaseEntity {

	/** 접수 채널 (guide 2.1). 채널별 요청은 ApplicationIntakeCommand 하나로 정규화된다 */
	enum Channel {
		STAFF, BROKER, PLATFORM
	}

	private String applicationNo;
	private long customerId;
	private String productCode;
	private long requestedAmount;
	private int termMonths;
	private int graceMonths;
	private int dueDay;
	private Long approvedAmount;
	private BigDecimal approvedRate;
	@Enumerated(EnumType.STRING)
	private ApplicationStatus status;
	private Long underwriterId;
	private LocalDate disbursedOn;
	@Enumerated(EnumType.STRING)
	private Channel channel;
	private String intakeSource;
	private String externalRef;
	private OffsetDateTime assignedAt;
	private LocalDate decidedOn;
	private String rejectReasonCode;

	protected LoanApplication() {
	}

	LoanApplication(String applicationNo, ApplicationIntakeCommand c) {
		this.applicationNo = applicationNo;
		this.customerId = c.customerId();
		this.productCode = c.productCode();
		this.requestedAmount = c.requestedAmount();
		this.termMonths = c.termMonths();
		this.graceMonths = c.graceMonths();
		this.dueDay = c.dueDay();
		this.status = ApplicationStatus.RECEIVED;
		this.channel = c.channel();
		this.intakeSource = c.intakeSource();
		this.externalRef = c.externalRef();
	}

	ApplicationStatus moveTo(ApplicationStatus next) {
		if (!status.canMoveTo(next)) {
			throw new BusinessException("TRANSITION_INVALID", status + " → " + next + " 전이는 허용되지 않습니다");
		}
		ApplicationStatus from = status;
		status = next;
		return from;
	}

	void assign(long staffId, OffsetDateTime at) {
		this.underwriterId = staffId;
		this.assignedAt = at;
	}

	void approve(long amount, BigDecimal rate, LocalDate on) {
		this.approvedAmount = amount;
		this.approvedRate = rate;
		this.decidedOn = on;
	}

	void rejected(String reasonCode, LocalDate on) {
		this.rejectReasonCode = reasonCode;
		this.decidedOn = on;
	}

	void disbursed(LocalDate on) {
		this.disbursedOn = on;
	}

	/** 승인 전 단계 (서류·동의를 바꿀 수 있는 상태) */
	boolean beforeDecision() {
		return switch (status) {
			case RECEIVED, PRE_SCREENING, ASSIGNED, UNDERWRITING -> true;
			default -> false;
		};
	}

	/** 진행 중 (중복 신청 판정 대상, R53): 거절·철회·실행 완료가 아닌 신청 */
	boolean active() {
		return switch (status) {
			case REJECTED, WITHDRAWN, DISBURSED -> false;
			default -> true;
		};
	}

	String applicationNo() {
		return applicationNo;
	}

	long customerId() {
		return customerId;
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

	int graceMonths() {
		return graceMonths;
	}

	int dueDay() {
		return dueDay;
	}

	Long approvedAmount() {
		return approvedAmount;
	}

	BigDecimal approvedRate() {
		return approvedRate;
	}

	ApplicationStatus status() {
		return status;
	}

	Long underwriterId() {
		return underwriterId;
	}

	LocalDate disbursedOn() {
		return disbursedOn;
	}

	Channel channel() {
		return channel;
	}

	String intakeSource() {
		return intakeSource;
	}

	String externalRef() {
		return externalRef;
	}

	OffsetDateTime assignedAt() {
		return assignedAt;
	}

	LocalDate decidedOn() {
		return decidedOn;
	}

	String rejectReasonCode() {
		return rejectReasonCode;
	}
}
