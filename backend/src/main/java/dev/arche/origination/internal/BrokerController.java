package dev.arche.origination.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 중개업자 관리·중개수수료 (R56). 조회는 심사 권한자, 변경은 broker.manage */
@RestController
@RequestMapping("/api/brokers")
@PreAuthorize("hasAuthority('application.review') or hasAuthority('broker.manage')")
class BrokerController {

	record RegisterRequest(@NotBlank String name, @NotBlank String registrationNo, String allowedIps,
			@NotNull BigDecimal rate) {
	}

	record UpdateRequest(Broker.Status status, String allowedIps) {
	}

	record RateRequest(@NotNull BigDecimal rate, @NotNull LocalDate validFrom, @NotBlank String reason) {
	}

	record ReasonRequest(String reason) {
	}

	record BrokerDto(long id, String brokerNo, String name, String registrationNo, String allowedIps, String status,
			BigDecimal currentRate) {
	}

	/** 발급 직후 한 번만 API 키 원문을 돌려준다 */
	record IssuedDto(BrokerDto broker, String apiKey) {
	}

	record RateDto(long id, BigDecimal rate, LocalDate validFrom, LocalDate validTo, String reason) {
	}

	record CommissionDto(long id, long brokerId, long applicationId, String applicationNo, String entryType,
			long baseAmount, BigDecimal rate, long amount, LocalDate occurredOn, String reasonCode, String reason,
			OffsetDateTime createdAt) {
	}

	record DetailDto(BrokerDto broker, List<RateDto> rates, List<CommissionDto> commissions, long settledTotal,
			long clawbackTotal) {
	}

	private final BrokerService brokers;
	private final LoanApplicationRepository applications;

	BrokerController(BrokerService brokers, LoanApplicationRepository applications) {
		this.brokers = brokers;
		this.applications = applications;
	}

	@GetMapping
	@Transactional(readOnly = true)
	List<BrokerDto> list() {
		return brokers.all().stream().map(this::dto).toList();
	}

	@GetMapping("/{id}")
	@Transactional(readOnly = true)
	DetailDto detail(@PathVariable long id) {
		List<CommissionDto> commissions = commissions(id);
		return new DetailDto(dto(brokers.find(id)),
				brokers.rates(id).stream()
						.map(r -> new RateDto(r.getId(), r.rate(), r.validFrom(), r.validTo(), r.reason())).toList(),
				commissions,
				commissions.stream().filter(c -> c.entryType().equals("SETTLEMENT")).mapToLong(CommissionDto::amount)
						.sum(),
				commissions.stream().filter(c -> c.entryType().equals("CLAWBACK")).mapToLong(CommissionDto::amount)
						.sum());
	}

	@GetMapping("/commissions")
	@Transactional(readOnly = true)
	List<CommissionDto> commissions(@RequestParam(required = false) Long brokerId) {
		return brokers.commissions(brokerId).stream().map(c -> new CommissionDto(c.getId(), c.brokerId(),
				c.applicationId(), applications.findById(c.applicationId()).map(LoanApplication::applicationNo)
						.orElse("-"),
				c.entryType().name(), c.baseAmount(), c.rate(), c.amount(), c.occurredOn(),
				c.reasonCode() == null ? null : c.reasonCode().name(), c.reason(), c.createdAt())).toList();
	}

	@PostMapping
	@PreAuthorize("hasAuthority('broker.manage')")
	@Transactional
	IssuedDto register(@Valid @RequestBody RegisterRequest r) {
		BrokerService.Issued i = brokers.register(r.name(), r.registrationNo(), r.allowedIps(), r.rate());
		return new IssuedDto(dto(i.broker()), i.apiKey());
	}

	@PostMapping("/{id}")
	@PreAuthorize("hasAuthority('broker.manage')")
	@Transactional
	BrokerDto update(@PathVariable long id, @RequestBody UpdateRequest r) {
		return dto(brokers.update(id, r.status(), r.allowedIps()));
	}

	@PostMapping("/{id}/api-key")
	@PreAuthorize("hasAuthority('broker.manage')")
	@Transactional
	IssuedDto rotate(@PathVariable long id) {
		BrokerService.Issued i = brokers.rotateKey(id);
		return new IssuedDto(dto(i.broker()), i.apiKey());
	}

	@PostMapping("/{id}/rates")
	@PreAuthorize("hasAuthority('broker.manage')")
	@Transactional
	RateDto changeRate(@PathVariable long id, @Valid @RequestBody RateRequest r) {
		BrokerCommissionRate t = brokers.changeRate(id, r.rate(), r.validFrom(), r.reason());
		return new RateDto(t.getId(), t.rate(), t.validFrom(), t.validTo(), t.reason());
	}

	/** 불완전판매 확인 → 수수료 환수 (실행일부터 1년 안) */
	@PostMapping("/commissions/applications/{applicationId}/mis-selling")
	@PreAuthorize("hasAuthority('broker.manage')")
	@Transactional
	List<CommissionDto> misSelling(@PathVariable long applicationId, @RequestBody ReasonRequest r) {
		BrokerCommission c = brokers.clawbackMisSelling(applicationId, r.reason());
		return commissions(c.brokerId()).stream().filter(x -> x.applicationId() == applicationId).toList();
	}

	/** 조기 완제 환수 점검 (일 배치와 같은 처리를 바로 실행) */
	@PostMapping("/commissions/clawback-scan")
	@PreAuthorize("hasAuthority('broker.manage')")
	java.util.Map<String, Integer> scan() {
		return java.util.Map.of("clawbacks", brokers.scanEarlyPayoff());
	}

	private BrokerDto dto(Broker b) {
		return new BrokerDto(b.getId(), b.brokerNo(), b.name(), b.registrationNo(), b.allowedIps(), b.status().name(),
				brokers.currentRate(b.getId()).orElse(null));
	}
}
