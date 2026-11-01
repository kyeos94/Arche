package dev.arche.creditinfo.internal;

import dev.arche.common.jpa.BaseEntity;
import dev.arche.integration.CreditReportingPort.RecordType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 일별 신용정보 보고 파일 (guide 12.2 "신용정보 보고 파일 생성·전송·결과 반영") */
@Entity
@Table(name = "credit_report_file")
class CreditReportFile extends BaseEntity {

	enum Status {
		GENERATED, SENT, RESULT_RECEIVED
	}

	private LocalDate baseDate;
	private String fileName;
	private int recordCount;
	private int byteLength;
	@Enumerated(EnumType.STRING)
	private Status status;
	private String receiptNo;
	private OffsetDateTime sentAt;
	private OffsetDateTime resultAt;
	private int errorCount;

	protected CreditReportFile() {
	}

	CreditReportFile(LocalDate baseDate, String fileName, int recordCount, int byteLength) {
		this.baseDate = baseDate;
		this.fileName = fileName;
		this.recordCount = recordCount;
		this.byteLength = byteLength;
		this.status = Status.GENERATED;
	}

	void sent(String receiptNo, OffsetDateTime at) {
		this.status = Status.SENT;
		this.receiptNo = receiptNo;
		this.sentAt = at;
	}

	void resultReceived(int errors, OffsetDateTime at) {
		this.status = Status.RESULT_RECEIVED;
		this.errorCount = errors;
		this.resultAt = at;
	}

	LocalDate baseDate() {
		return baseDate;
	}

	String fileName() {
		return fileName;
	}

	int recordCount() {
		return recordCount;
	}

	int byteLength() {
		return byteLength;
	}

	Status status() {
		return status;
	}

	String receiptNo() {
		return receiptNo;
	}

	OffsetDateTime sentAt() {
		return sentAt;
	}

	OffsetDateTime resultAt() {
		return resultAt;
	}

	int errorCount() {
		return errorCount;
	}
}

@Entity
@Table(name = "credit_report_record")
class CreditReportRecord extends BaseEntity {

	private long fileId;
	private int seq;
	private long accountId;
	@Enumerated(EnumType.STRING)
	private RecordType recordType;
	private LocalDate eventDate;
	private long balance;
	private int delinquencyDays;
	private String resultCode;
	private String resultMessage;

	protected CreditReportRecord() {
	}

	CreditReportRecord(long fileId, int seq, long accountId, RecordType recordType, LocalDate eventDate, long balance,
			int delinquencyDays) {
		this.fileId = fileId;
		this.seq = seq;
		this.accountId = accountId;
		this.recordType = recordType;
		this.eventDate = eventDate;
		this.balance = balance;
		this.delinquencyDays = delinquencyDays;
	}

	void result(String code, String message) {
		this.resultCode = code;
		this.resultMessage = message;
	}

	long fileId() {
		return fileId;
	}

	int seq() {
		return seq;
	}

	long accountId() {
		return accountId;
	}

	RecordType recordType() {
		return recordType;
	}

	LocalDate eventDate() {
		return eventDate;
	}

	long balance() {
		return balance;
	}

	int delinquencyDays() {
		return delinquencyDays;
	}

	String resultCode() {
		return resultCode;
	}

	String resultMessage() {
		return resultMessage;
	}
}

/** 계약별로 기관에 반영된 상태 (정상 결과를 받은 보고만) */
@Entity
@Table(name = "credit_report_state")
class CreditReportState extends BaseEntity {

	private long accountId;
	private boolean opened;
	private boolean delinquent;
	private boolean closed;
	private LocalDate lastReported;

	protected CreditReportState() {
	}

	CreditReportState(long accountId) {
		this.accountId = accountId;
	}

	ReportPlanner.Reported reported() {
		return new ReportPlanner.Reported(opened, delinquent, closed);
	}

	void apply(RecordType type, LocalDate on) {
		ReportPlanner.Reported r = ReportPlanner.apply(reported(), type);
		this.opened = r.opened();
		this.delinquent = r.delinquent();
		this.closed = r.closed();
		this.lastReported = on;
	}

	long accountId() {
		return accountId;
	}

	boolean closed() {
		return closed;
	}
}
