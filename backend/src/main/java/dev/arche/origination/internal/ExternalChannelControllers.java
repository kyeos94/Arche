package dev.arche.origination.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.common.BusinessException;
import dev.arche.customer.CustomerView;
import dev.arche.customer.Customers;
import dev.arche.integration.IdentityVerificationPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 외부(중개업자·플랫폼·고객)가 부르는 수신 API. 세션·CSRF가 없는 /api/integration 아래에 두고 각자 인증한다
 * (중개업자: API 키 + IP, 플랫폼: 플랫폼별 API 키, 고객: 일회용 링크 토큰 + 본인인증). ADR 0007.
 * 요청 DTO의 toString은 개인정보를 찍지 않는다.
 */
final class ExternalChannelControllers {

	private ExternalChannelControllers() {
	}

	/** 접수 결과 (모든 채널 공통) */
	record IntakeResult(String applicationNo, String status, String rejectReasonCode) {
		static IntakeResult of(LoanApplication a) {
			return new IntakeResult(a.applicationNo(), a.status().name(), a.rejectReasonCode());
		}
	}

	static DocumentType docType(String code) {
		try {
			return DocumentType.valueOf(code);
		} catch (IllegalArgumentException e) {
			throw new BusinessException("DOCUMENT_NOT_FOUND", "알 수 없는 서류 종류입니다: " + code);
		}
	}
}

/** 중개업자 API 접수 (guide 2.1: API 키·IP 허용, 첨부 파일 업로드, 수수료 정산 대상 표시) */
@RestController
@RequestMapping("/api/integration/broker/applications")
class BrokerIntakeController {

	record BrokerApplicationRequest(@NotBlank @Size(max = 60) String brokerRef, @NotBlank String name,
			@NotBlank String residentNo, @NotNull LocalDate birthDate, String phone, String address,
			@NotBlank String productCode, @Positive long requestedAmount, @Positive int termMonths, int graceMonths,
			int dueDay, boolean creditInquiryConsent, @Size(max = 60) String consentRef) {

		@Override
		public String toString() {
			return "BrokerApplicationRequest[" + brokerRef + "]";
		}
	}

	private final BrokerService brokers;
	private final ApplicationService applications;
	private final DocumentService documents;
	private final LoanApplicationRepository repository;
	private final Customers customers;
	private final BusinessDates businessDates;

	BrokerIntakeController(BrokerService brokers, ApplicationService applications, DocumentService documents,
			LoanApplicationRepository repository, Customers customers, BusinessDates businessDates) {
		this.brokers = brokers;
		this.applications = applications;
		this.documents = documents;
		this.repository = repository;
		this.customers = customers;
		this.businessDates = businessDates;
	}

	@PostMapping
	@Transactional
	ExternalChannelControllers.IntakeResult apply(@RequestHeader(name = "X-Broker-Key", required = false) String key,
			@Valid @RequestBody BrokerApplicationRequest r, HttpServletRequest http) {
		Broker b = authenticate(key, http);
		CustomerView c = customers.registerOrFind(
				new Customers.NewCustomer(r.name(), r.residentNo(), r.birthDate(), r.phone(), r.address()));
		ApplicationIntakeCommand.Consent consent = r.creditInquiryConsent()
				? new ApplicationIntakeCommand.Consent(ApplicationConsent.Channel.BROKER, businessDates.today(),
						"BROKER-" + b.brokerNo() + "-" + (r.consentRef() == null ? r.brokerRef() : r.consentRef()))
				: null;
		return ExternalChannelControllers.IntakeResult.of(applications.intake(new ApplicationIntakeCommand(
				LoanApplication.Channel.BROKER, b.brokerNo(), r.brokerRef(), b.getId(), c.id(), r.productCode(),
				r.requestedAmount(), r.termMonths(), r.graceMonths(), r.dueDay(), consent)));
	}

	@GetMapping("/{applicationNo}")
	@Transactional(readOnly = true)
	ExternalChannelControllers.IntakeResult status(@RequestHeader(name = "X-Broker-Key", required = false) String key,
			@PathVariable String applicationNo, HttpServletRequest http) {
		return ExternalChannelControllers.IntakeResult.of(own(authenticate(key, http), applicationNo));
	}

	@PostMapping(value = "/{applicationNo}/documents/{docType}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Transactional
	ApplicationController.DocumentDto upload(@RequestHeader(name = "X-Broker-Key", required = false) String key,
			@PathVariable String applicationNo, @PathVariable String docType, @RequestParam("file") MultipartFile file,
			HttpServletRequest http) throws IOException {
		LoanApplication a = own(authenticate(key, http), applicationNo);
		return ApplicationController.DocumentDto.of(documents.upload(a, ExternalChannelControllers.docType(docType),
				ApplicationDocument.Via.BROKER, file.getOriginalFilename(), file.getContentType(), file.getBytes()));
	}

	private Broker authenticate(String key, HttpServletRequest http) {
		return brokers.authenticate(key, http.getRemoteAddr()).orElseThrow(
				() -> new BusinessException("UNAUTHENTICATED", "중개업자 인증에 실패했습니다 (API 키·허용 IP·상태 확인)"));
	}

	/** 다른 중개업자의 신청은 없는 것처럼 404 */
	private LoanApplication own(Broker b, String applicationNo) {
		return repository.findByApplicationNo(applicationNo).filter(a -> b.getId().equals(a.brokerId()))
				.orElseThrow(() -> new BusinessException("APPLICATION_NOT_FOUND", "신청이 없습니다"));
	}
}

/** 대출 비교 플랫폼 3단계 수신 API (guide 2.1, R57) */
@RestController
@RequestMapping("/api/integration/platform/{platformCode}")
class PlatformController {

	record LimitInquiryRequest(@NotBlank @Size(max = 60) String platformRef, @NotBlank String name,
			@NotBlank String residentNo, @NotNull LocalDate birthDate, @NotBlank String productCode,
			@Positive long requestedAmount, @Positive int termMonths, boolean creditInquiryConsent,
			@Size(max = 30) String consentRef) {

		@Override
		public String toString() {
			return "LimitInquiryRequest[" + platformRef + "]";
		}
	}

	record LimitInquiryResult(String inquiryNo, String platformRef, boolean offered, Long offeredLimit,
			BigDecimal offeredRate, String declineReason, LocalDate validUntil) {
		static LimitInquiryResult of(PlatformInquiry q) {
			return new LimitInquiryResult(q.inquiryNo(), q.platformRef(), q.offered(), q.offeredLimit(),
					q.offeredRate(), q.declineReason(), q.validUntil());
		}
	}

	record ApplyRequest(@NotBlank String platformRef, @NotBlank String name, @NotBlank String residentNo,
			@NotNull LocalDate birthDate, String phone, String address, @Positive long requestedAmount,
			@Positive int termMonths, int graceMonths, int dueDay) {

		@Override
		public String toString() {
			return "ApplyRequest[" + platformRef + "]";
		}
	}

	private final PlatformService platform;

	PlatformController(PlatformService platform) {
		this.platform = platform;
	}

	@PostMapping("/limit-inquiries")
	LimitInquiryResult limit(@PathVariable String platformCode,
			@RequestHeader(name = "X-Platform-Key", required = false) String key,
			@Valid @RequestBody LimitInquiryRequest r) {
		authenticate(platformCode, key);
		return LimitInquiryResult.of(platform.limitInquiry(platformCode,
				new PlatformService.LimitRequest(r.platformRef(), r.name(), r.residentNo(), r.birthDate(),
						r.productCode(), r.requestedAmount(), r.termMonths(), r.creditInquiryConsent(),
						r.consentRef())));
	}

	@PostMapping("/applications")
	ExternalChannelControllers.IntakeResult apply(@PathVariable String platformCode,
			@RequestHeader(name = "X-Platform-Key", required = false) String key, @Valid @RequestBody ApplyRequest r) {
		authenticate(platformCode, key);
		return ExternalChannelControllers.IntakeResult.of(platform.apply(platformCode,
				new PlatformService.ApplyRequest(r.platformRef(), r.name(), r.residentNo(), r.birthDate(), r.phone(),
						r.address(), r.requestedAmount(), r.termMonths(), r.graceMonths(), r.dueDay())));
	}

	/** 결과 통보를 놓친 플랫폼의 조회용 (pull) */
	@GetMapping("/applications/{platformRef}")
	@Transactional(readOnly = true)
	LimitInquiryResult inquiry(@PathVariable String platformCode, @PathVariable String platformRef,
			@RequestHeader(name = "X-Platform-Key", required = false) String key) {
		authenticate(platformCode, key);
		return LimitInquiryResult.of(platform.inquiry(platformCode, platformRef));
	}

	private void authenticate(String platformCode, String key) {
		if (!platform.authenticate(platformCode, key)) {
			throw new BusinessException("UNAUTHENTICATED", "플랫폼 인증에 실패했습니다");
		}
	}
}

/** 고객 랜딩 페이지 API (guide 11.3, R58) */
@RestController
@RequestMapping("/api/integration/landing/{token}")
class LandingController {

	record DocumentItem(String docType, String label, boolean required, String status) {
	}

	record LandingDto(String applicationNo, String productName, String customerName, String status,
			OffsetDateTime expiresAt, boolean consentGiven, List<DocumentItem> documents) {
	}

	record ResultRequest(@NotBlank String transactionId) {
	}

	record ConsentRequest(boolean creditInquiry) {
	}

	private final LandingService landing;

	LandingController(LandingService landing) {
		this.landing = landing;
	}

	@GetMapping
	LandingDto view(@PathVariable String token) {
		LandingService.View v = landing.view(token);
		return new LandingDto(v.application().applicationNo(), v.productName(), v.customerNameMasked(),
				v.verification().status().name(), v.verification().expiresAt(), v.consentGiven(),
				v.documents().stream().map(d -> new DocumentItem(d.docType().name(), d.docType().label, d.required(),
						d.status().name())).toList());
	}

	@PostMapping("/identity")
	IdentityVerificationPort.Session start(@PathVariable String token) {
		return landing.startIdentity(token);
	}

	@PostMapping("/identity/result")
	LandingDto result(@PathVariable String token, @Valid @RequestBody ResultRequest r) {
		landing.verify(token, r.transactionId());
		return view(token);
	}

	@PostMapping("/consents")
	LandingDto consent(@PathVariable String token, @RequestBody ConsentRequest r) {
		landing.consent(token, r.creditInquiry());
		return view(token);
	}

	@PostMapping(value = "/documents/{docType}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	LandingDto upload(@PathVariable String token, @PathVariable String docType,
			@RequestParam("file") MultipartFile file) throws IOException {
		landing.upload(token, ExternalChannelControllers.docType(docType), file.getOriginalFilename(),
				file.getContentType(), file.getBytes());
		return view(token);
	}

	@PostMapping("/complete")
	java.util.Map<String, String> complete(@PathVariable String token) {
		return java.util.Map.of("status", landing.complete(token).status().name());
	}
}
