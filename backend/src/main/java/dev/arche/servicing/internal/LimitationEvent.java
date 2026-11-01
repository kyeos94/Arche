package dev.arche.servicing.internal;

import dev.arche.common.jpa.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * 소멸시효 중단·연장 사유 (정책서 5장, G13). 입금(일부 변제)은 account_event에서 가져오므로 여기에는 법적 사유만 둔다.
 * 법조치 상세(사건번호·법원·기일)는 범위 밖이라 날짜와 메모만 기록한다.
 */
@Entity
@Table(name = "limitation_event")
class LimitationEvent extends BaseEntity {

	enum Type {
		/** 지급명령 신청: 중단, 그날부터 다시 5년 */
		PAYMENT_ORDER_FILED,
		/** 압류: 중단, 그날부터 다시 5년 */
		SEIZURE,
		/** 판결·지급명령 확정: 그날부터 10년 */
		JUDGMENT_CONFIRMED
	}

	private long accountId;
	@Enumerated(EnumType.STRING)
	private Type type;
	private LocalDate eventDate;
	private String memo;
	/** 법조치 취하·각하 등으로 중단 효력이 없어진 날 (R51). 행은 지우지 않는다 */
	private LocalDate voidedOn;
	private String voidReason;

	protected LimitationEvent() {
	}

	LimitationEvent(long accountId, Type type, LocalDate eventDate, String memo) {
		this.accountId = accountId;
		this.type = type;
		this.eventDate = eventDate;
		this.memo = memo;
	}

	Type type() {
		return type;
	}

	long accountId() {
		return accountId;
	}

	void voidOn(LocalDate on, String reason) {
		this.voidedOn = on;
		this.voidReason = reason;
	}

	boolean voided() {
		return voidedOn != null;
	}

	String voidReason() {
		return voidReason;
	}

	LocalDate eventDate() {
		return eventDate;
	}

	String memo() {
		return memo;
	}
}
