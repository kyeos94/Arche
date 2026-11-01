package dev.arche.creditinfo.internal;

import dev.arche.integration.CreditReportingPort;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 신용정보 보고 파일 이력·수동 실행 (운영 관리: 배치 관리 권한) */
@RestController
@RequestMapping("/api/credit-reports")
class CreditReportController {

	record RunRequest(LocalDate baseDate) {
	}

	record FileDto(long id, LocalDate baseDate, String fileName, int recordCount, int byteLength, String status,
			String receiptNo, OffsetDateTime sentAt, OffsetDateTime resultAt, int errorCount) {
		static FileDto of(CreditReportFile f) {
			return new FileDto(f.getId(), f.baseDate(), f.fileName(), f.recordCount(), f.byteLength(),
					f.status().name(), f.receiptNo(), f.sentAt(), f.resultAt(), f.errorCount());
		}
	}

	record RecordDto(int seq, long accountId, String recordType, LocalDate eventDate, long balance,
			int delinquencyDays, String resultCode, String resultMessage) {
	}

	record Detail(FileDto file, List<RecordDto> records) {
	}

	record Overview(LocalDate defaultBaseDate, List<FileDto> files) {
	}

	private final CreditReportService service;
	private final CreditReportFileRepository files;
	private final CreditReportRecordRepository records;
	private final CreditReportingPort port;

	CreditReportController(CreditReportService service, CreditReportFileRepository files,
			CreditReportRecordRepository records, CreditReportingPort port) {
		this.service = service;
		this.files = files;
		this.records = records;
		this.port = port;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('admin.manage')")
	@Transactional(readOnly = true)
	Overview list() {
		return new Overview(service.defaultBaseDate(),
				files.findTop100ByOrderByBaseDateDesc().stream().map(FileDto::of).toList());
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('admin.manage')")
	@Transactional(readOnly = true)
	Detail detail(@PathVariable long id) {
		return new Detail(FileDto.of(service.file(id)),
				records.findByFileIdOrderBySeq(id).stream().map(r -> new RecordDto(r.seq(), r.accountId(),
						r.recordType().name(), r.eventDate(), r.balance(), r.delinquencyDays(), r.resultCode(),
						r.resultMessage())).toList());
	}

	/** 생성 → 전송 → 결과 반영을 한 번에 (기준일 생략 시 업무일자 − 1일) */
	@PostMapping("/run")
	@PreAuthorize("hasAuthority('admin.manage')")
	FileDto run(@RequestBody(required = false) RunRequest body) {
		LocalDate base = body == null || body.baseDate() == null ? service.defaultBaseDate() : body.baseDate();
		return FileDto.of(service.runDaily(base));
	}

	@PostMapping("/{id}/send")
	@PreAuthorize("hasAuthority('admin.manage')")
	FileDto send(@PathVariable long id) {
		return FileDto.of(service.send(id));
	}

	@PostMapping("/{id}/result")
	@PreAuthorize("hasAuthority('admin.manage')")
	FileDto result(@PathVariable long id) {
		return FileDto.of(service.applyResult(id));
	}

	/** 보고 파일 원본 (고정길이 MS949) */
	@GetMapping("/{id}/file")
	@PreAuthorize("hasAuthority('admin.manage')")
	ResponseEntity<byte[]> download(@PathVariable long id) {
		CreditReportFile f = service.file(id);
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment().filename(f.fileName()).build().toString())
				.contentType(MediaType.APPLICATION_OCTET_STREAM).body(port.read(f.fileName()));
	}
}
