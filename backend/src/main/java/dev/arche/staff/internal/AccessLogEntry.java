package dev.arche.staff.internal;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.Immutable;

/** 접근 기록 (수정 불가) */
@Entity
@Immutable
@Table(name = "access_log")
class AccessLogEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	private Long staffId;
	private String loginId;
	private OffsetDateTime accessedAt;
	private String ip;
	private String action;
	private String targetType;
	private String targetId;
	private String reason;

	protected AccessLogEntry() {
	}

	AccessLogEntry(Long staffId, String loginId, OffsetDateTime accessedAt, String ip, String action,
			String targetType, String targetId, String reason) {
		this.staffId = staffId;
		this.loginId = loginId;
		this.accessedAt = accessedAt;
		this.ip = ip;
		this.action = action;
		this.targetType = targetType;
		this.targetId = targetId;
		this.reason = reason;
	}

	Long staffId() {
		return staffId;
	}

	String loginId() {
		return loginId;
	}

	OffsetDateTime accessedAt() {
		return accessedAt;
	}

	String ip() {
		return ip;
	}

	String action() {
		return action;
	}

	String targetType() {
		return targetType;
	}

	String targetId() {
		return targetId;
	}

	String reason() {
		return reason;
	}
}
