package dev.arche.origination.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.common.BusinessException;
import dev.arche.customer.CustomerView;
import dev.arche.customer.Customers;
import dev.arche.integration.IdentityVerificationPort;
import dev.arche.integration.MessagingPort;
import dev.arche.product.ProductCatalog;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 본인인증 기반 랜딩 페이지 흐름 (guide 11.3, R58):
 * 일회용 링크(30분) → 본인인증(가짜 인증 화면) → 인증 결과의 이름·생년월일이 신청 고객과 같을 때만 동의·서류 제출 → 완료.
 */
@Service
class LandingService {

	static final Duration TTL = Duration.ofMinutes(30);
	private static final SecureRandom RANDOM = new SecureRandom();

	record Issued(IdentityVerification verification, String path, String url, String providerId) {

		@Override
		public String toString() {
			return "Issued[" + verification.getId() + "]";
		}
	}

	/** 고객 화면용 (본인인증 전에는 마스킹된 이름·상품만) */
	record View(IdentityVerification verification, LoanApplication application, String productName,
			String customerNameMasked, boolean consentGiven, List<ApplicationDocument> documents) {
	}

	private final IdentityVerificationRepository verifications;
	private final ApplicationService applications;
	private final ScreeningService screening;
	private final DocumentService documents;
	private final Customers customers;
	private final ProductCatalog products;
	private final IdentityVerificationPort identity;
	private final MessagingPort messaging;
	private final BusinessDates businessDates;
	private final Clock clock;
	private final String baseUrl;

	LandingService(IdentityVerificationRepository verifications, ApplicationService applications,
			ScreeningService screening, DocumentService documents, Customers customers, ProductCatalog products,
			IdentityVerificationPort identity, MessagingPort messaging, BusinessDates businessDates, Clock clock,
			@Value("${arche.landing.base-url:http://localhost:5173}") String baseUrl) {
		this.verifications = verifications;
		this.applications = applications;
		this.screening = screening;
		this.documents = documents;
		this.customers = customers;
		this.products = products;
		this.identity = identity;
		this.messaging = messaging;
		this.businessDates = businessDates;
		this.clock = clock;
		this.baseUrl = baseUrl;
	}

	/** 링크 발급 + 문자 발송. 같은 신청의 이전 미완료 링크는 무효화한다 */
	@Transactional
	Issued issue(long applicationId) {
		LoanApplication a = applications.find(applicationId);
		if (!a.beforeDecision()) {
			throw new BusinessException("APPLICATION_STATE", "승인·거절 전 신청에만 링크를 보낼 수 있습니다");
		}
		verifications.findByApplicationIdOrderByIdDesc(applicationId).forEach(IdentityVerification::revoke);
		byte[] raw = new byte[32];
		RANDOM.nextBytes(raw);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
		IdentityVerification v = verifications.save(new IdentityVerification(applicationId, a.customerId(),
				BrokerService.hash(token), now().plus(TTL)));
		String path = "/landing/" + token;
		String url = baseUrl + path;
		MessagingPort.Sent sent = messaging.send(a.customerId(),
				"[예시대부] 대출 신청 확인·동의 링크입니다. 30분 안에 열어 주세요. " + url);
		return new Issued(v, path, url, sent.providerId());
	}

	@Transactional(readOnly = true)
	View view(String token) {
		IdentityVerification v = find(token);
		v.requireUsable(now());
		LoanApplication a = applications.find(v.applicationId());
		CustomerView c = customers.get(v.customerId());
		boolean verified = v.status() == IdentityVerification.Status.VERIFIED;
		return new View(v, a, products.current(a.productCode()).name(), maskName(c.name()),
				screening.validCreditConsent(a.getId(), businessDates.today()).isPresent(),
				verified ? documents.list(a.getId()) : List.of());
	}

	@Transactional(readOnly = true)
	IdentityVerificationPort.Session startIdentity(String token) {
		IdentityVerification v = find(token);
		v.requireUsable(now());
		return identity.start("/landing/" + token);
	}

	/**
	 * 인증 결과 대조. 실패·불일치는 시도 횟수를 올리고(3회면 링크 폐기) 예외를 던진다 — 시도 횟수는 롤백하지 않는다
	 */
	@Transactional(noRollbackFor = BusinessException.class)
	IdentityVerification verify(String token, String transactionId) {
		IdentityVerification v = find(token);
		v.requireUsable(now());
		if (v.status() == IdentityVerification.Status.VERIFIED) {
			return v;
		}
		var result = identity.result(transactionId);
		if (result.isEmpty()) {
			int left = v.failed();
			throw new BusinessException("IDENTITY_FAILED", "본인인증에 실패했습니다 (남은 시도 " + left + "회)");
		}
		CustomerView c = customers.get(v.customerId());
		if (!c.name().equals(result.get().name()) || !c.birthDate().equals(result.get().birthDate())) {
			int left = v.failed();
			throw new BusinessException("IDENTITY_MISMATCH", "신청하신 분의 정보와 인증 결과가 다릅니다 (남은 시도 " + left + "회)");
		}
		v.verified(BrokerService.hash(result.get().connectingInfo()), now());
		return v;
	}

	@Transactional
	ApplicationConsent consent(String token, boolean agree) {
		IdentityVerification v = find(token);
		v.requireVerified(now());
		if (!agree) {
			throw new BusinessException("CONSENT_REQUIRED", "신용조회에 동의해야 심사를 진행할 수 있습니다");
		}
		return screening.recordConsent(applications.find(v.applicationId()), ApplicationConsent.Channel.LANDING,
				businessDates.today(), "LANDING-" + v.getId());
	}

	@Transactional
	ApplicationDocument upload(String token, DocumentType type, String fileName, String contentType,
			byte[] content) {
		IdentityVerification v = find(token);
		v.requireVerified(now());
		return documents.upload(applications.find(v.applicationId()), type, ApplicationDocument.Via.LANDING, fileName,
				contentType, content);
	}

	@Transactional
	IdentityVerification complete(String token) {
		IdentityVerification v = find(token);
		v.requireVerified(now());
		v.complete(now());
		return v;
	}

	List<IdentityVerification> forApplication(long applicationId) {
		return verifications.findByApplicationIdOrderByIdDesc(applicationId);
	}

	/** 김고객 → 김*객, 홍길동전 → 홍**전, 두 글자는 끝 글자만 가림 */
	static String maskName(String name) {
		if (name == null || name.length() < 2) {
			return "*";
		}
		if (name.length() == 2) {
			return name.charAt(0) + "*";
		}
		return name.charAt(0) + "*".repeat(name.length() - 2) + name.charAt(name.length() - 1);
	}

	private IdentityVerification find(String token) {
		if (token == null || token.length() < 20) {
			throw new BusinessException("LANDING_NOT_FOUND", "링크가 올바르지 않습니다");
		}
		return verifications.findByTokenHash(BrokerService.hash(token))
				.orElseThrow(() -> new BusinessException("LANDING_NOT_FOUND", "링크가 올바르지 않습니다"));
	}

	private OffsetDateTime now() {
		return OffsetDateTime.now(clock);
	}
}
