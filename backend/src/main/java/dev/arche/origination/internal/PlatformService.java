package dev.arche.origination.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.common.BusinessException;
import dev.arche.common.crypto.BlindIndexer;
import dev.arche.common.jpa.NumberGenerator;
import dev.arche.customer.CustomerView;
import dev.arche.customer.Customers;
import dev.arche.integration.CreditBureauPort;
import dev.arche.integration.LoanPlatformPort;
import dev.arche.origination.ApplicationDecided;
import dev.arche.product.ProductCatalog;
import dev.arche.product.ProductView;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대출 비교 플랫폼 3단계 (guide 2.1, R57): ① 한도조회(가심사) → ② 신청(정규화된 접수) → ③ 결과 통보(비동기).
 * 플랫폼마다 LoanPlatformPort 어댑터가 하나씩 있고(전략 패턴), 코드로 고른다.
 */
@Service
class PlatformService {

	record LimitRequest(String platformRef, String name, String residentNo, LocalDate birthDate, String productCode,
			long requestedAmount, int termMonths, boolean creditInquiryConsent, String consentRef) {

		@Override
		public String toString() {
			return "LimitRequest[" + platformRef + "]";
		}
	}

	record ApplyRequest(String platformRef, String name, String residentNo, LocalDate birthDate, String phone,
			String address, long requestedAmount, int termMonths, int graceMonths, int dueDay) {

		@Override
		public String toString() {
			return "ApplyRequest[" + platformRef + "]";
		}
	}

	private final Map<String, LoanPlatformPort> ports;
	private final PlatformInquiryRepository inquiries;
	private final PlatformResultNoticeRepository notices;
	private final ApplicationService applicationService;
	private final ProductCatalog products;
	private final Customers customers;
	private final CreditBureauPort creditBureau;
	private final BlindIndexer indexer;
	private final BusinessDates businessDates;
	private final NumberGenerator numbers;
	private final Clock clock;

	PlatformService(List<LoanPlatformPort> ports, PlatformInquiryRepository inquiries,
			PlatformResultNoticeRepository notices,
			ApplicationService applicationService, ProductCatalog products, Customers customers,
			CreditBureauPort creditBureau, BlindIndexer indexer, BusinessDates businessDates, NumberGenerator numbers,
			Clock clock) {
		this.ports = ports.stream().collect(Collectors.toMap(LoanPlatformPort::platformCode, Function.identity()));
		this.inquiries = inquiries;
		this.notices = notices;
		this.applicationService = applicationService;
		this.products = products;
		this.customers = customers;
		this.creditBureau = creditBureau;
		this.indexer = indexer;
		this.businessDates = businessDates;
		this.numbers = numbers;
		this.clock = clock;
	}

	boolean authenticate(String platformCode, String apiKey) {
		LoanPlatformPort p = ports.get(platformCode);
		return p != null && p.authenticate(apiKey);
	}

	/** ① 한도조회. 같은 플랫폼 참조로 다시 오면 처음 결과를 돌려준다. 동의 없이는 신용조회를 하지 않는다 */
	@Transactional
	PlatformInquiry limitInquiry(String platformCode, LimitRequest r) {
		port(platformCode);
		var existing = inquiries.findByPlatformCodeAndPlatformRef(platformCode, r.platformRef());
		if (existing.isPresent()) {
			return existing.get();
		}
		if (!r.creditInquiryConsent() || r.consentRef() == null || r.consentRef().isBlank()) {
			throw new BusinessException("CONSENT_REQUIRED", "신용조회 동의가 있어야 한도조회를 할 수 있습니다");
		}
		ProductView product = products.current(r.productCode());
		if ("N1".equals(product.code())) {
			throw new BusinessException("PRODUCT_NOT_APPLICABLE", "매입채권 상품은 신청을 받지 않습니다");
		}
		if (r.termMonths() < product.minTermMonths() || r.termMonths() > product.maxTermMonths()) {
			throw new BusinessException("PRODUCT_TERM", product.name() + " 기간은 " + product.minTermMonths() + " ~ "
					+ product.maxTermMonths() + "개월입니다");
		}
		LocalDate today = businessDates.today();
		// 미성년자는 신용조회 자체를 하지 않는다
		CreditBureauPort.Report report = Period.between(r.birthDate(), today).getYears() >= ScreeningRules.MIN_AGE
				? creditBureau.inquire(new CreditBureauPort.Inquiry(r.residentNo(), "한도조회"))
				: null;
		PlatformOffer.Offer offer = PlatformOffer.evaluate(product, r.birthDate(), today, report);
		return inquiries.save(new PlatformInquiry(numbers.next("platform_inquiry_no_seq", "Q"), platformCode,
				r.platformRef(), indexer.index(r.residentNo()), product.code(), r.requestedAmount(), r.termMonths(),
				"PLATFORM-" + platformCode + "-" + r.consentRef(), report == null ? null : report.inquiryRef(),
				report == null ? null : report.grade(), offer, today));
	}

	/** ② 신청: 한도조회 결과(유효기간·한도·동일인)를 확인하고 정규화된 접수로 넘긴다 */
	@Transactional
	LoanApplication apply(String platformCode, ApplyRequest r) {
		port(platformCode);
		PlatformInquiry q = inquiries.findByPlatformCodeAndPlatformRef(platformCode, r.platformRef())
				.orElseThrow(() -> new BusinessException("PLATFORM_INQUIRY_NOT_FOUND", "한도조회 기록이 없습니다"));
		if (q.applicationId() != null) {
			return applicationService.find(q.applicationId());
		}
		if (!q.offered()) {
			throw new BusinessException("PLATFORM_NOT_OFFERED", "한도가 제시되지 않은 조회입니다: " + q.declineReason());
		}
		if (businessDates.today().isAfter(q.validUntil())) {
			throw new BusinessException("PLATFORM_INQUIRY_EXPIRED", "한도조회 결과의 유효기간(" + q.validUntil() + ")이 지났습니다");
		}
		if (!indexer.index(r.residentNo()).equals(q.residentNoBidx())) {
			throw new BusinessException("PLATFORM_IDENTITY_MISMATCH", "한도조회한 고객과 신청 고객이 다릅니다");
		}
		if (r.requestedAmount() > q.offeredLimit()) {
			throw new BusinessException("PLATFORM_OVER_LIMIT", "신청 금액이 제시 한도(" + q.offeredLimit() + "원)를 넘습니다");
		}
		CustomerView c = customers.registerOrFind(
				new Customers.NewCustomer(r.name(), r.residentNo(), r.birthDate(), r.phone(), r.address()));
		LoanApplication a = applicationService.intake(new ApplicationIntakeCommand(LoanApplication.Channel.PLATFORM,
				platformCode, r.platformRef(), null, c.id(), q.productCode(), r.requestedAmount(), r.termMonths(),
				r.graceMonths(), r.dueDay(), new ApplicationIntakeCommand.Consent(ApplicationConsent.Channel.PLATFORM,
						q.inquiredOn(), q.consentRef())));
		q.linkApplication(a.getId());
		return a;
	}

	PlatformInquiry inquiry(String platformCode, String platformRef) {
		port(platformCode);
		return inquiries.findByPlatformCodeAndPlatformRef(platformCode, platformRef)
				.orElseThrow(() -> new BusinessException("PLATFORM_INQUIRY_NOT_FOUND", "한도조회 기록이 없습니다"));
	}

	/**
	 * ③ 결과 통보. 결과별로 한 번 전달되면 다시 보내지 않고, 실패하면 재통보 때 attempts를 늘린다.
	 */
	@Transactional
	PlatformResultNotice notifyResult(long applicationId, String result) {
		LoanApplication a = applicationService.find(applicationId);
		if (a.channel() != LoanApplication.Channel.PLATFORM) {
			return null;
		}
		PlatformResultNotice n = notices.findByApplicationIdAndResult(applicationId, result)
				.orElseGet(() -> new PlatformResultNotice(applicationId, a.intakeSource(), result));
		if (n.delivered()) {
			return n;
		}
		LoanPlatformPort.Delivery d = port(a.intakeSource()).notifyResult(new LoanPlatformPort.ResultNotice(
				a.externalRef(), a.applicationNo(), result, a.approvedAmount(), a.approvedRate(), a.rejectReasonCode()));
		n.attempted(d.delivered(), d.receiptId(), OffsetDateTime.now(clock));
		return notices.save(n);
	}

	/** 직원의 재통보 (전달 실패분) */
	@Transactional
	List<PlatformResultNotice> resend(long applicationId) {
		for (PlatformResultNotice n : notices.findByApplicationIdOrderById(applicationId)) {
			if (!n.delivered()) {
				notifyResult(applicationId, n.result());
			}
		}
		return notices.findByApplicationIdOrderById(applicationId);
	}

	List<PlatformResultNotice> notices(long applicationId) {
		return notices.findByApplicationIdOrderById(applicationId);
	}

	java.util.Optional<PlatformInquiry> inquiryOf(long applicationId) {
		return inquiries.findByApplicationId(applicationId);
	}

	private LoanPlatformPort port(String code) {
		LoanPlatformPort p = code == null ? null : ports.get(code);
		if (p == null) {
			throw new BusinessException("PLATFORM_NOT_FOUND", "연계되지 않은 플랫폼입니다");
		}
		return p;
	}
}

/** 결과 확정 → 플랫폼 결과 통보 (커밋 후 비동기, 발행 기록으로 유실 방지) */
@Component
class PlatformResultListener {

	private final PlatformService platform;

	PlatformResultListener(PlatformService platform) {
		this.platform = platform;
	}

	@ApplicationModuleListener
	void on(ApplicationDecided e) {
		platform.notifyResult(e.applicationId(), e.result());
	}
}
