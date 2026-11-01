package dev.arche.collection.internal;

import dev.arche.collection.internal.LegalActionRules.Stage;
import dev.arche.collection.internal.LegalActionRules.Type;
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
import org.hibernate.annotations.Immutable;

/**
 * 법조치 1건 (지급명령·압류·경매, R51). 법원·사건번호·대상은 자유 입력의 일반형 필드다.
 */
@Entity
@Table(name = "legal_action")
class LegalAction extends BaseEntity {

	private String actionNo;
	private long accountId;
	@Enumerated(EnumType.STRING)
	private Type type;
	@Enumerated(EnumType.STRING)
	private Stage status;
	private String courtName;
	private String caseRef;
	private String targetDesc;
	private Long claimAmount;
	private LocalDate filedOn;
	private LocalDate closedOn;
	private String memo;

	protected LegalAction() {
	}

	LegalAction(String actionNo, long accountId, Type type, LocalDate filedOn, String courtName, String caseRef,
			String targetDesc, Long claimAmount, String memo) {
		this.actionNo = actionNo;
		this.accountId = accountId;
		this.type = type;
		this.status = Stage.FILED;
		this.filedOn = filedOn;
		this.courtName = courtName;
		this.caseRef = caseRef;
		this.targetDesc = targetDesc;
		this.claimAmount = claimAmount;
		this.memo = memo;
	}

	void moveTo(Stage stage, LocalDate on) {
		this.status = stage;
		if (LegalActionRules.TERMINAL.contains(stage)) {
			this.closedOn = on;
		}
	}

	String actionNo() {
		return actionNo;
	}

	long accountId() {
		return accountId;
	}

	Type type() {
		return type;
	}

	Stage status() {
		return status;
	}

	String courtName() {
		return courtName;
	}

	String caseRef() {
		return caseRef;
	}

	String targetDesc() {
		return targetDesc;
	}

	Long claimAmount() {
		return claimAmount;
	}

	LocalDate filedOn() {
		return filedOn;
	}

	LocalDate closedOn() {
		return closedOn;
	}

	String memo() {
		return memo;
	}
}

/** 법조치 단계 이력 (추가 전용) */
@Entity
@Immutable
@Table(name = "legal_action_stage")
class LegalActionStage {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	private long legalActionId;
	@Enumerated(EnumType.STRING)
	private Stage stage;
	private LocalDate stageDate;
	private String memo;
	/** 이 단계가 남긴 소멸시효 사유 (servicing limitation_event.id) */
	private Long limitationEventId;
	private Long staffId;
	private OffsetDateTime createdAt;
	private String createdBy;

	protected LegalActionStage() {
	}

	LegalActionStage(long legalActionId, Stage stage, LocalDate stageDate, String memo, Long limitationEventId,
			Long staffId, OffsetDateTime createdAt, String createdBy) {
		this.legalActionId = legalActionId;
		this.stage = stage;
		this.stageDate = stageDate;
		this.memo = memo;
		this.limitationEventId = limitationEventId;
		this.staffId = staffId;
		this.createdAt = createdAt;
		this.createdBy = createdBy;
	}

	Long getId() {
		return id;
	}

	Stage stage() {
		return stage;
	}

	LocalDate stageDate() {
		return stageDate;
	}

	String memo() {
		return memo;
	}

	Long limitationEventId() {
		return limitationEventId;
	}

	String createdBy() {
		return createdBy;
	}
}

/** 법조치 비용 (추가 전용, R52). 계약 비용(COST)으로 적재한 기록 */
@Entity
@Immutable
@Table(name = "legal_action_cost")
class LegalActionCost {

	enum CostType {
		/** 인지대 등 법원 납부 */
		COURT_FEE,
		/** 송달료 */
		SERVICE_FEE,
		/** 집행·감정·매각 수수료 등 */
		EXECUTION_FEE,
		/** 대리인 비용 */
		AGENT_FEE,
		OTHER
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	private long legalActionId;
	@Enumerated(EnumType.STRING)
	private CostType costType;
	private long amount;
	private LocalDate incurredOn;
	private LocalDate chargedOn;
	private String memo;
	private Long staffId;
	private OffsetDateTime createdAt;
	private String createdBy;

	protected LegalActionCost() {
	}

	LegalActionCost(long legalActionId, CostType costType, Money amount, LocalDate incurredOn, LocalDate chargedOn,
			String memo, Long staffId, OffsetDateTime createdAt, String createdBy) {
		this.legalActionId = legalActionId;
		this.costType = costType;
		this.amount = amount.won();
		this.incurredOn = incurredOn;
		this.chargedOn = chargedOn;
		this.memo = memo;
		this.staffId = staffId;
		this.createdAt = createdAt;
		this.createdBy = createdBy;
	}

	Long getId() {
		return id;
	}

	CostType costType() {
		return costType;
	}

	Money amount() {
		return Money.won(amount);
	}

	LocalDate incurredOn() {
		return incurredOn;
	}

	LocalDate chargedOn() {
		return chargedOn;
	}

	String memo() {
		return memo;
	}
}
