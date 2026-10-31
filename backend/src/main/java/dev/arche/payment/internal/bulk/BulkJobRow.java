package dev.arche.payment.internal.bulk;

import dev.arche.common.jpa.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** 일괄 처리 행: 검증 결과(VALID/INVALID)와 실행 결과(SUCCEEDED/FAILED) */
@Entity
@Table(name = "bulk_job_row")
class BulkJobRow extends BaseEntity {

	private long jobId;
	private int rowNo;
	private String payload;
	private String status;
	private String message;

	protected BulkJobRow() {
	}

	BulkJobRow(long jobId, int rowNo, String payload, boolean valid, String message) {
		this.jobId = jobId;
		this.rowNo = rowNo;
		this.payload = payload;
		this.status = valid ? "VALID" : "INVALID";
		this.message = message;
	}

	void succeeded(String message) {
		this.status = "SUCCEEDED";
		this.message = message;
	}

	void failed(String message) {
		this.status = "FAILED";
		this.message = message;
	}

	long jobId() {
		return jobId;
	}

	int rowNo() {
		return rowNo;
	}

	String payload() {
		return payload;
	}

	String status() {
		return status;
	}

	String message() {
		return message;
	}
}
