package dev.arche.payment.internal.bulk;

import dev.arche.common.BusinessException;
import dev.arche.staff.StaffDirectory;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

interface BulkJobRepository extends JpaRepository<BulkJob, Long> {
}

interface BulkJobRowRepository extends JpaRepository<BulkJobRow, Long> {

	List<BulkJobRow> findByJobIdOrderByRowNo(long jobId);
}

@Service
class BulkPaymentService {

	record RowDto(int rowNo, String status, String message, String payload) {
	}

	record JobDto(long id, String type, String fileName, String status, int totalRows, int validRows,
			int successRows, int failedRows, List<RowDto> rows) {
	}

	private final PaymentBulkProcessor processor;
	private final BulkJobRepository jobs;
	private final BulkJobRowRepository rows;
	private final StaffDirectory staff;
	private final TaskExecutor executor;
	private final TransactionTemplate rowTx;

	BulkPaymentService(PaymentBulkProcessor processor, BulkJobRepository jobs, BulkJobRowRepository rows,
			StaffDirectory staff, TaskExecutor applicationTaskExecutor, PlatformTransactionManager tx) {
		this.processor = processor;
		this.jobs = jobs;
		this.rows = rows;
		this.staff = staff;
		this.executor = applicationTaskExecutor;
		this.rowTx = new TransactionTemplate(tx);
		this.rowTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@Transactional
	JobDto upload(MultipartFile file) throws IOException {
		if (file.isEmpty()) {
			throw new BusinessException("BULK_FILE_INVALID", "파일이 비었습니다");
		}
		var parsed = processor.read(file.getInputStream());
		int valid = (int) parsed.stream().filter(AbstractBulkProcessor.Parsed::valid).count();
		BulkJob job = jobs.save(new BulkJob(processor.type(), file.getOriginalFilename(), parsed.size(), valid,
				staff.current().id()));
		List<BulkJobRow> rowEntities = new ArrayList<>();
		for (var p : parsed) {
			rowEntities.add(new BulkJobRow(job.getId(), p.rowNo(), p.payload(), p.valid(), p.error()));
		}
		rows.saveAll(rowEntities);
		return dto(job);
	}

	/** 비동기 실행. 행마다 별도 트랜잭션, 실패 행은 기록하고 계속 (guide 12.3) */
	@Transactional
	JobDto execute(long jobId) {
		BulkJob job = job(jobId);
		if (!"VALIDATED".equals(job.status())) {
			throw new BusinessException("BULK_STATE", "이미 실행한 작업입니다");
		}
		job.start();
		Runnable task = new DelegatingSecurityContextRunnable(() -> run(jobId));
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				executor.execute(task);
			}
		});
		return dto(job);
	}

	void run(long jobId) {
		int success = 0;
		int failed = 0;
		List<BulkJobRow> all = rows.findByJobIdOrderByRowNo(jobId);
		for (BulkJobRow row : all) {
			if (!"VALID".equals(row.status())) {
				failed++;
				continue;
			}
			try {
				String message = rowTx.execute(s -> processor.execute(processor.fromPayload(row.payload())));
				row.succeeded(message);
				success++;
			} catch (RuntimeException e) {
				row.failed(e.getMessage());
				failed++;
			}
			BulkJobRow done = row;
			rowTx.executeWithoutResult(s -> rows.save(done));
			if ((success + failed) % 50 == 0) {
				int ok = success;
				int ng = failed;
				rowTx.executeWithoutResult(s -> job(jobId).progress(ok, ng));
			}
		}
		int ok = success;
		int ng = failed;
		rowTx.executeWithoutResult(s -> job(jobId).finish(ok, ng));
	}

	@Transactional(readOnly = true)
	JobDto get(long jobId) {
		return dto(job(jobId));
	}

	@Transactional(readOnly = true)
	byte[] failures(long jobId) {
		List<String[]> out = new ArrayList<>();
		for (BulkJobRow r : rows.findByJobIdOrderByRowNo(jobId)) {
			if ("INVALID".equals(r.status()) || "FAILED".equals(r.status())) {
				String[] cols = r.payload().split("\\|", -1);
				String[] line = new String[processor.headers().size() + 2];
				for (int i = 0; i < processor.headers().size(); i++) {
					line[i] = i < cols.length ? cols[i] : "";
				}
				line[line.length - 2] = String.valueOf(r.rowNo());
				line[line.length - 1] = r.message();
				out.add(line);
			}
		}
		return processor.write(out);
	}

	byte[] template() {
		return processor.template();
	}

	private BulkJob job(long id) {
		return jobs.findById(id).orElseThrow(() -> new BusinessException("BULK_NOT_FOUND", "작업이 없습니다"));
	}

	private JobDto dto(BulkJob job) {
		List<RowDto> rowDtos = rows.findByJobIdOrderByRowNo(job.getId()).stream()
				.map(r -> new RowDto(r.rowNo(), r.status(), r.message(), r.payload())).toList();
		return new JobDto(job.getId(), job.type(), job.fileName(), job.status(), job.totalRows(), job.validRows(),
				job.successRows(), job.failedRows(), rowDtos);
	}
}

@RestController
@RequestMapping("/api/payments/bulk")
@PreAuthorize("hasAuthority('payment.register')")
class BulkController {

	private static final MediaType XLSX = MediaType
			.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

	private final BulkPaymentService service;

	BulkController(BulkPaymentService service) {
		this.service = service;
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	BulkPaymentService.JobDto upload(@RequestParam("file") MultipartFile file) throws IOException {
		return service.upload(file);
	}

	@PostMapping("/{id}/execute")
	BulkPaymentService.JobDto execute(@PathVariable long id) {
		return service.execute(id);
	}

	@GetMapping("/{id}")
	BulkPaymentService.JobDto get(@PathVariable long id) {
		return service.get(id);
	}

	@GetMapping("/{id}/failures")
	ResponseEntity<byte[]> failures(@PathVariable long id) {
		return download(service.failures(id), "payment-bulk-" + id + "-failures.xlsx");
	}

	@GetMapping("/template")
	ResponseEntity<byte[]> template() {
		return download(service.template(), "payment-bulk-template.xlsx");
	}

	private static ResponseEntity<byte[]> download(byte[] body, String name) {
		return ResponseEntity.ok().contentType(XLSX)
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build()
						.toString())
				.body(body);
	}
}
