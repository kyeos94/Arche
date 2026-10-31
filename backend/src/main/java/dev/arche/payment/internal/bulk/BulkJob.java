package dev.arche.payment.internal.bulk;

import dev.arche.common.jpa.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** 엑셀 일괄 처리 작업 (guide 12.3) */
@Entity
@Table(name = "bulk_job")
class BulkJob extends BaseEntity {

	private String type;
	private String fileName;
	private String status;
	private int totalRows;
	private int validRows;
	private int successRows;
	private int failedRows;
	private long requestedBy;

	protected BulkJob() {
	}

	BulkJob(String type, String fileName, int totalRows, int validRows, long requestedBy) {
		this.type = type;
		this.fileName = fileName;
		this.totalRows = totalRows;
		this.validRows = validRows;
		this.requestedBy = requestedBy;
		this.status = "VALIDATED";
	}

	void start() {
		this.status = "RUNNING";
	}

	void finish(int success, int failed) {
		this.successRows = success;
		this.failedRows = failed;
		this.status = "COMPLETED";
	}

	void progress(int success, int failed) {
		this.successRows = success;
		this.failedRows = failed;
	}

	String type() {
		return type;
	}

	String fileName() {
		return fileName;
	}

	String status() {
		return status;
	}

	int totalRows() {
		return totalRows;
	}

	int validRows() {
		return validRows;
	}

	int successRows() {
		return successRows;
	}

	int failedRows() {
		return failedRows;
	}

	long requestedBy() {
		return requestedBy;
	}
}
