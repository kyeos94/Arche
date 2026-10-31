package dev.arche.origination.internal;

import dev.arche.origination.ApplicationStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/applications")
@PreAuthorize("hasAuthority('application.review')")
class ApplicationController {

	record ReceiveRequest(@NotNull Long customerId, @NotBlank String productCode, @Positive long requestedAmount,
			@Positive int termMonths, int graceMonths, int dueDay) {
	}

	record TransitionRequest(@NotNull ApplicationStatus to, String reason) {
	}

	record ApprovalRequest(@Positive long amount, @NotNull BigDecimal rate) {
	}

	record HistoryDto(String fromStatus, String toStatus, String actor, String reason, OffsetDateTime changedAt) {
	}

	record ApplicationDto(long id, String applicationNo, long customerId, String productCode, long requestedAmount,
			int termMonths, int graceMonths, int dueDay, Long approvedAmount, BigDecimal approvedRate, String status,
			Long underwriterId, LocalDate disbursedOn) {
		static ApplicationDto of(LoanApplication a) {
			return new ApplicationDto(a.getId(), a.applicationNo(), a.customerId(), a.productCode(),
					a.requestedAmount(), a.termMonths(), a.graceMonths(), a.dueDay(), a.approvedAmount(),
					a.approvedRate(), a.status().name(), a.underwriterId(), a.disbursedOn());
		}
	}

	private final ApplicationService service;
	private final LoanApplicationRepository applications;

	ApplicationController(ApplicationService service, LoanApplicationRepository applications) {
		this.service = service;
		this.applications = applications;
	}

	@PostMapping
	ApplicationDto receive(@Valid @RequestBody ReceiveRequest r) {
		return ApplicationDto.of(service.receive(r.customerId(), r.productCode(), r.requestedAmount(),
				r.termMonths(), r.graceMonths(), r.dueDay()));
	}

	@GetMapping
	@Transactional(readOnly = true)
	List<ApplicationDto> list() {
		return applications.findAllByOrderByIdDesc().stream().map(ApplicationDto::of).toList();
	}

	@GetMapping("/{id}")
	@Transactional(readOnly = true)
	ApplicationDto get(@PathVariable long id) {
		return ApplicationDto.of(service.find(id));
	}

	@GetMapping("/{id}/history")
	@Transactional(readOnly = true)
	List<HistoryDto> history(@PathVariable long id) {
		return service.history(id).stream()
				.map(h -> new HistoryDto(h.fromStatus(), h.toStatus(), h.actor(), h.reason(), h.changedAt())).toList();
	}

	@PostMapping("/{id}/transitions")
	ApplicationDto transition(@PathVariable long id, @Valid @RequestBody TransitionRequest r) {
		return ApplicationDto.of(service.transition(id, r.to(), r.reason()));
	}

	@PostMapping("/{id}/approval")
	ApplicationDto approve(@PathVariable long id, @Valid @RequestBody ApprovalRequest r) {
		return ApplicationDto.of(service.approve(id, r.amount(), r.rate()));
	}

	@PostMapping("/{id}/disbursement")
	ApplicationDto disburse(@PathVariable long id) {
		return ApplicationDto.of(service.disburse(id));
	}
}
