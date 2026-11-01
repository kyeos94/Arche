package dev.arche.origination.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.common.BusinessException;
import dev.arche.customer.CustomerView;
import dev.arche.customer.Customers;
import dev.arche.integration.CreditBureauPort;
import dev.arche.origination.ApplicationStatus;
import dev.arche.origination.ServicingHistory;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 동의 확인 → 신용조회 → 자동 거절 규칙 (정책서 7장, R53, guide 11.2).
 */
@Service
class ScreeningService {

	private final LoanApplicationRepository applications;
	private final ApplicationConsentRepository consents;
	private final CreditInquiryRepository inquiries;
	private final ScreeningResultRepository results;
	private final Customers customers;
	private final ServicingHistory servicing;
	private final CreditBureauPort creditBureau;
	private final BusinessDates businessDates;
	private final Clock clock;

	ScreeningService(LoanApplicationRepository applications, ApplicationConsentRepository consents,
			CreditInquiryRepository inquiries, ScreeningResultRepository results, Customers customers,
			ServicingHistory servicing, CreditBureauPort creditBureau, BusinessDates businessDates, Clock clock) {
		this.applications = applications;
		this.consents = consents;
		this.inquiries = inquiries;
		this.results = results;
		this.customers = customers;
		this.servicing = servicing;
		this.creditBureau = creditBureau;
		this.businessDates = businessDates;
		this.clock = clock;
	}

	/** 사전심사 규칙을 모두 판정해 저장한다. 거절 여부는 호출자가 결과로 판단한다 */
	@Transactional
	List<ScreeningRules.Result> screen(LoanApplication a) {
		LocalDate today = businessDates.today();
		CustomerView customer = customers.get(a.customerId());
		List<LoanApplication> others = applications.findByCustomerId(a.customerId()).stream()
				.filter(o -> !o.getId().equals(a.getId())).toList();
		List<String> activeOthers = others.stream().filter(LoanApplication::active)
				.map(LoanApplication::applicationNo).toList();
		List<LocalDate> rejectedOn = others.stream()
				.filter(o -> o.status() == ApplicationStatus.REJECTED && o.decidedOn() != null)
				.map(LoanApplication::decidedOn).toList();
		List<ScreeningRules.Result> all = new ArrayList<>(ScreeningRules.beforeCreditBureau(customer.birthDate(), today,
				activeOthers, rejectedOn, servicing.adverseAccounts(a.customerId())));
		boolean prior = ScreeningRules.anyRejected(all);
		Optional<ApplicationConsent> consent = validCreditConsent(a.getId(), today);
		CreditBureauPort.Report report = !prior && consent.isPresent()
				? inquire(a.getId(), a.customerId(), consent.get().ref(), today)
				: null;
		all.add(ScreeningRules.creditBureau(prior, consent.isPresent(), report));
		for (ScreeningRules.Result r : all) {
			results.save(new ScreeningResult(a.getId(), r, today));
		}
		return all;
	}

	/** 심사 중 수동 신용조회 (동의를 사전심사 뒤에 받은 경우). 자동 거절은 하지 않고 결과만 남긴다 */
	@Transactional
	CreditInquiry inquireManually(LoanApplication a) {
		if (!a.beforeDecision()) {
			throw new BusinessException("APPLICATION_STATE", "승인·거절 전 신청만 조회할 수 있습니다");
		}
		LocalDate today = businessDates.today();
		ApplicationConsent consent = validCreditConsent(a.getId(), today).orElseThrow(
				() -> new BusinessException("CONSENT_REQUIRED", "유효한 신용조회 동의가 없어 조회할 수 없습니다"));
		inquire(a.getId(), a.customerId(), consent.ref(), today);
		return inquiries.findByApplicationIdOrderByIdDesc(a.getId()).getFirst();
	}

	/**
	 * 동의 참조를 확인한 뒤 조회하고 결과를 저장한다. 동의 없는 호출을 막기 위해 consentRef는 필수다 (guide 11.2)
	 */
	CreditBureauPort.Report inquire(@Nullable Long applicationId, long customerId, String consentRef,
			LocalDate today) {
		String identifier = customers.residentNoForCreditInquiry(customerId, consentRef);
		CreditBureauPort.Report r = creditBureau.inquire(new CreditBureauPort.Inquiry(identifier, "대출 심사"));
		inquiries.save(new CreditInquiry(applicationId, customerId, consentRef, r.inquiryRef(), r.grade(),
				r.currentlyDelinquent(), r.openLoanCount(), r.totalDebt(), today));
		return r;
	}

	@Transactional
	ApplicationConsent recordConsent(LoanApplication a, ApplicationConsent.Channel channel, LocalDate agreedOn,
			@Nullable String evidence) {
		if (!a.beforeDecision()) {
			throw new BusinessException("APPLICATION_STATE", "승인·거절 전 신청에만 동의를 기록할 수 있습니다");
		}
		return consents.save(new ApplicationConsent(a.getId(), ApplicationConsent.Type.CREDIT_INQUIRY, channel,
				agreedOn, evidence));
	}

	@Transactional
	ApplicationConsent withdraw(long applicationId, long consentId) {
		ApplicationConsent c = consents.findById(consentId).filter(x -> x.applicationId() == applicationId)
				.orElseThrow(() -> new BusinessException("CONSENT_NOT_FOUND", "동의 이력이 없습니다"));
		c.withdraw(OffsetDateTime.now(clock));
		return c;
	}

	Optional<ApplicationConsent> validCreditConsent(long applicationId, LocalDate day) {
		return consents.findByApplicationIdAndConsentTypeOrderByIdDesc(applicationId,
				ApplicationConsent.Type.CREDIT_INQUIRY).stream().filter(c -> c.validOn(day)).findFirst();
	}

	List<ApplicationConsent> consents(long applicationId) {
		return consents.findByApplicationIdOrderById(applicationId);
	}

	List<ScreeningResult> results(long applicationId) {
		return results.findByApplicationIdOrderById(applicationId);
	}

	List<CreditInquiry> inquiries(long applicationId) {
		return inquiries.findByApplicationIdOrderByIdDesc(applicationId);
	}
}
