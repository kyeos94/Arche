package dev.arche.origination.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.common.BusinessException;
import dev.arche.customer.CustomerView;
import dev.arche.customer.Customers;
import dev.arche.origination.ApplicationStatus;
import dev.arche.staff.CurrentStaff;
import dev.arche.staff.StaffDirectory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/applications")
@PreAuthorize("hasAuthority('application.review')")
class ApplicationController {

	/**
	 * 직원 입력 접수. creditInquiryConsent = 상담 중 서면·녹취로 신용조회 동의를 받았는지 (증빙 번호는 consentEvidence)
	 */
	record ReceiveRequest(@NotNull Long customerId, @NotBlank String productCode, @Positive long requestedAmount,
			@Positive int termMonths, int graceMonths, int dueDay, Boolean creditInquiryConsent,
			String consentEvidence) {
	}

	record TransitionRequest(@NotNull ApplicationStatus to, String reason, String reasonCode, Long underwriterId) {
	}

	record ApprovalRequest(@Positive long amount, @NotNull BigDecimal rate) {
	}

	record AssignmentRequest(@NotNull Long underwriterId, String reason) {
	}

	record ConsentRequest(String evidence) {
	}

	record NoteRequest(String note) {
	}

	record HistoryDto(String fromStatus, String toStatus, String actor, String reason, OffsetDateTime changedAt) {
	}

	record ApplicationDto(long id, String applicationNo, long customerId, String customerName, String productCode,
			long requestedAmount, int termMonths, int graceMonths, int dueDay, Long approvedAmount,
			BigDecimal approvedRate, String status, Long underwriterId, String underwriterName, LocalDate disbursedOn,
			String channel, String intakeSource, String externalRef, OffsetDateTime assignedAt, LocalDate decidedOn,
			String rejectReasonCode, OffsetDateTime receivedAt) {
	}

	record ScreeningDto(String rule, String label, String outcome, String detail, LocalDate screenedOn) {
	}

	record ConsentDto(long id, String type, String channel, LocalDate agreedOn, LocalDate validUntil,
			String evidence, OffsetDateTime withdrawnAt, boolean valid) {
	}

	record CreditInquiryDto(long id, String inquiryRef, int grade, boolean currentlyDelinquent, int openLoanCount,
			long totalDebt, LocalDate inquiredOn) {
	}

	record DocumentDto(long id, String docType, String label, boolean required, String status, String receivedVia,
			String fileName, String contentType, Long sizeBytes, OffsetDateTime receivedAt, String reviewedBy,
			OffsetDateTime reviewedAt, String note) {
		static DocumentDto of(ApplicationDocument d) {
			return new DocumentDto(d.getId(), d.docType().name(), d.docType().label, d.required(), d.status().name(),
					d.receivedVia() == null ? null : d.receivedVia().name(), d.fileName(), d.contentType(),
					d.sizeBytes(), d.receivedAt(), d.reviewedBy(), d.reviewedAt(), d.note());
		}
	}

	/** 심사 화면 한 번에: 신청 + 고객(마스킹) + 자동 거절 결과 + 동의 + 신용조회 + 서류 */
	record ReviewDto(ApplicationDto application, CustomerView customer, List<ScreeningDto> screening,
			List<ConsentDto> consents, List<CreditInquiryDto> creditInquiries, List<DocumentDto> documents,
			List<String> missingRequiredDocuments, boolean creditConsentValid) {
	}

	private final ApplicationService service;
	private final ScreeningService screening;
	private final DocumentService documents;
	private final LoanApplicationRepository applications;
	private final Customers customers;
	private final StaffDirectory staff;
	private final BusinessDates businessDates;

	ApplicationController(ApplicationService service, ScreeningService screening, DocumentService documents,
			LoanApplicationRepository applications, Customers customers, StaffDirectory staff,
			BusinessDates businessDates) {
		this.service = service;
		this.screening = screening;
		this.documents = documents;
		this.applications = applications;
		this.customers = customers;
		this.staff = staff;
		this.businessDates = businessDates;
	}

	@PostMapping
	ApplicationDto receive(@Valid @RequestBody ReceiveRequest r) {
		ApplicationIntakeCommand.Consent consent = Boolean.TRUE.equals(r.creditInquiryConsent())
				? new ApplicationIntakeCommand.Consent(ApplicationConsent.Channel.STAFF, businessDates.today(),
						r.consentEvidence())
				: null;
		return dto(service.intake(new ApplicationIntakeCommand(LoanApplication.Channel.STAFF, null, null, null,
				r.customerId(), r.productCode(), r.requestedAmount(), r.termMonths(), r.graceMonths(), r.dueDay(),
				consent)));
	}

	@GetMapping
	@Transactional(readOnly = true)
	List<ApplicationDto> list(@RequestParam(required = false) ApplicationStatus status) {
		List<LoanApplication> rows = status == null ? applications.findAllByOrderByIdDesc()
				: applications.findByStatusOrderByIdDesc(status);
		Map<Long, String> customerNames = new HashMap<>();
		Map<Long, String> staffNames = new HashMap<>();
		return rows.stream().map(a -> dto(a, customerNames, staffNames)).toList();
	}

	@GetMapping("/{id}")
	@Transactional(readOnly = true)
	ApplicationDto get(@PathVariable long id) {
		return dto(service.find(id));
	}

	@GetMapping("/{id}/review")
	@Transactional(readOnly = true)
	ReviewDto review(@PathVariable long id) {
		LoanApplication a = service.find(id);
		LocalDate today = businessDates.today();
		return new ReviewDto(dto(a), customers.get(a.customerId()),
				screening.results(id).stream().map(s -> new ScreeningDto(s.ruleCode().name(), s.ruleCode().label,
						s.outcome().name(), s.detail(), s.screenedOn())).toList(),
				screening.consents(id).stream().map(c -> new ConsentDto(c.getId(), c.consentType().name(),
						c.channel().name(), c.agreedOn(), c.validUntil(), c.evidence(), c.withdrawnAt(),
						c.validOn(today))).toList(),
				screening.inquiries(id).stream().map(ApplicationController::inquiry).toList(),
				documents.list(id).stream().map(DocumentDto::of).toList(),
				documents.missingRequired(id).stream().map(d -> d.label).toList(),
				screening.validCreditConsent(id, today).isPresent());
	}

	@GetMapping("/{id}/history")
	@Transactional(readOnly = true)
	List<HistoryDto> history(@PathVariable long id) {
		return service.history(id).stream()
				.map(h -> new HistoryDto(h.fromStatus(), h.toStatus(), h.actor(), h.reason(), h.changedAt())).toList();
	}

	@PostMapping("/{id}/transitions")
	ApplicationDto transition(@PathVariable long id, @Valid @RequestBody TransitionRequest r) {
		return dto(service.transition(id, r.to(), r.reason(), r.reasonCode(), r.underwriterId()));
	}

	@PostMapping("/{id}/assignment")
	@PreAuthorize("hasAuthority('application.assign')")
	ApplicationDto reassign(@PathVariable long id, @Valid @RequestBody AssignmentRequest r) {
		return dto(service.reassign(id, r.underwriterId(), r.reason()));
	}

	@PostMapping("/{id}/approval")
	ApplicationDto approve(@PathVariable long id, @Valid @RequestBody ApprovalRequest r) {
		return dto(service.approve(id, r.amount(), r.rate()));
	}

	@PostMapping("/{id}/disbursement")
	ApplicationDto disburse(@PathVariable long id) {
		return dto(service.disburse(id));
	}

	// ------------------------------------------------------------------ 동의·신용조회

	@PostMapping("/{id}/consents")
	@Transactional
	ConsentDto consent(@PathVariable long id, @RequestBody ConsentRequest r) {
		ApplicationConsent c = screening.recordConsent(service.find(id), ApplicationConsent.Channel.STAFF,
				businessDates.today(), r.evidence());
		return new ConsentDto(c.getId(), c.consentType().name(), c.channel().name(), c.agreedOn(), c.validUntil(),
				c.evidence(), c.withdrawnAt(), true);
	}

	@PostMapping("/{id}/consents/{consentId}/withdrawal")
	@Transactional
	ConsentDto withdraw(@PathVariable long id, @PathVariable long consentId) {
		ApplicationConsent c = screening.withdraw(id, consentId);
		return new ConsentDto(c.getId(), c.consentType().name(), c.channel().name(), c.agreedOn(), c.validUntil(),
				c.evidence(), c.withdrawnAt(), false);
	}

	@PostMapping("/{id}/credit-inquiries")
	@Transactional
	CreditInquiryDto inquire(@PathVariable long id) {
		return inquiry(screening.inquireManually(service.find(id)));
	}

	// ------------------------------------------------------------------ 서류 징구 (R54)

	@GetMapping("/{id}/documents")
	@Transactional(readOnly = true)
	List<DocumentDto> documents(@PathVariable long id) {
		return documents.list(id).stream().map(DocumentDto::of).toList();
	}

	@PostMapping(value = "/{id}/documents/{docId}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Transactional
	DocumentDto upload(@PathVariable long id, @PathVariable long docId, @RequestParam("file") MultipartFile file)
			throws IOException {
		return DocumentDto.of(documents.upload(service.find(id), docId, ApplicationDocument.Via.UPLOAD,
				file.getOriginalFilename(), file.getContentType(), file.getBytes()));
	}

	@PostMapping("/{id}/documents/{docId}/paper")
	@Transactional
	DocumentDto paper(@PathVariable long id, @PathVariable long docId, @RequestBody NoteRequest r) {
		return DocumentDto.of(documents.receivePaper(service.find(id), docId, r.note()));
	}

	@PostMapping("/{id}/documents/{docId}/verify")
	@Transactional
	DocumentDto verify(@PathVariable long id, @PathVariable long docId) {
		return DocumentDto.of(documents.verify(service.find(id), docId));
	}

	@PostMapping("/{id}/documents/{docId}/reject")
	@Transactional
	DocumentDto reject(@PathVariable long id, @PathVariable long docId, @RequestBody NoteRequest r) {
		return DocumentDto.of(documents.reject(service.find(id), docId, r.note()));
	}

	@GetMapping("/{id}/documents/{docId}/file")
	ResponseEntity<byte[]> download(@PathVariable long id, @PathVariable long docId) {
		DocumentService.FileContent f = documents.file(id, docId);
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment().filename(f.fileName(), StandardCharsets.UTF_8).build()
								.toString())
				.contentType(MediaType.parseMediaType(f.contentType())).body(f.bytes());
	}

	// ------------------------------------------------------------------ 변환

	private static CreditInquiryDto inquiry(CreditInquiry c) {
		return new CreditInquiryDto(c.getId(), c.inquiryRef(), c.grade(), c.currentlyDelinquent(), c.openLoanCount(),
				c.totalDebt(), c.inquiredOn());
	}

	private ApplicationDto dto(LoanApplication a) {
		return dto(a, new HashMap<>(), new HashMap<>());
	}

	private ApplicationDto dto(LoanApplication a, Map<Long, String> customerNames, Map<Long, String> staffNames) {
		String customerName = customerNames.computeIfAbsent(a.customerId(), id -> {
			try {
				return customers.get(id).name();
			} catch (BusinessException e) {
				return "-";
			}
		});
		String underwriterName = a.underwriterId() == null ? null
				: staffNames.computeIfAbsent(a.underwriterId(),
						id -> staff.byId(id).map(CurrentStaff::name).orElse("#" + id));
		return new ApplicationDto(a.getId(), a.applicationNo(), a.customerId(), customerName, a.productCode(),
				a.requestedAmount(), a.termMonths(), a.graceMonths(), a.dueDay(), a.approvedAmount(), a.approvedRate(),
				a.status().name(), a.underwriterId(), underwriterName, a.disbursedOn(), a.channel().name(),
				a.intakeSource(), a.externalRef(), a.assignedAt(), a.decidedOn(), a.rejectReasonCode(),
				a.getCreatedAt());
	}
}
