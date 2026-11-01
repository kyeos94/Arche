package dev.arche.collection.internal;

import dev.arche.collection.internal.LegalActionRules.Stage;
import dev.arche.collection.internal.LegalActionRules.Type;
import dev.arche.common.Money;
import dev.arche.servicing.AccountRef;
import dev.arche.servicing.LoanAccounts;
import dev.arche.staff.DataScope;
import dev.arche.staff.StaffDirectory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
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

/**
 * 법조치 상세 (지급명령·압류·경매) API (R51·R52). 등록·단계·비용은 소멸시효 중단 사유 기록 권한(R37, 채권팀장·본부장).
 */
@RestController
@RequestMapping("/api")
class LegalActionController {

	record OpenRequest(@NotNull Type type, @NotNull LocalDate filedOn, @Size(max = 100) String courtName,
			@Size(max = 50) String caseRef, @Size(max = 200) String targetDesc, Long claimAmount,
			@Size(max = 500) String memo) {
	}

	record StageRequest(@NotNull Stage stage, @NotNull LocalDate date, @Size(max = 200) String memo) {
	}

	record CostRequest(@NotNull LegalActionCost.CostType costType, @Positive long amount,
			@NotNull LocalDate incurredOn, @Size(max = 200) String memo) {
	}

	record StageDto(long id, String stage, LocalDate date, String memo, Long limitationEventId, String createdBy) {
	}

	record CostDto(long id, String costType, long amount, LocalDate incurredOn, LocalDate chargedOn, String memo) {
	}

	record LegalActionDto(long id, String actionNo, long accountId, String accountNo, String type, String status,
			String courtName, String caseRef, String targetDesc, Long claimAmount, LocalDate filedOn,
			LocalDate closedOn, String memo, long totalCost, List<String> nextStages, List<StageDto> stages,
			List<CostDto> costs) {
	}

	private final LegalActionService service;
	private final AccountAccess access;
	private final LoanAccounts accounts;
	private final StaffDirectory staff;

	LegalActionController(LegalActionService service, AccountAccess access, LoanAccounts accounts,
			StaffDirectory staff) {
		this.service = service;
		this.access = access;
		this.accounts = accounts;
		this.staff = staff;
	}

	@GetMapping("/loans/{id}/legal-actions")
	@PreAuthorize("hasAuthority('loan.read')")
	@Transactional(readOnly = true)
	List<LegalActionDto> ofAccount(@PathVariable long id) {
		AccountRef account = access.visible(id);
		return service.of(id).stream().map(a -> dto(a, account.accountNo())).toList();
	}

	@PostMapping("/loans/{id}/legal-actions")
	@PreAuthorize("hasAuthority('collection.legal')")
	@Transactional
	LegalActionDto open(@PathVariable long id, @Valid @RequestBody OpenRequest body) {
		AccountRef account = access.visible(id);
		LegalAction a = service.open(account, new LegalActionService.OpenCommand(body.type(), body.filedOn(),
				body.courtName(), body.caseRef(), body.targetDesc(), body.claimAmount(), body.memo()));
		return dto(a, account.accountNo());
	}

	@PostMapping("/legal-actions/{actionId}/stages")
	@PreAuthorize("hasAuthority('collection.legal')")
	@Transactional
	LegalActionDto move(@PathVariable long actionId, @Valid @RequestBody StageRequest body) {
		AccountRef account = access.visible(service.action(actionId).accountId());
		return dto(service.move(actionId, body.stage(), body.date(), body.memo()), account.accountNo());
	}

	@PostMapping("/legal-actions/{actionId}/costs")
	@PreAuthorize("hasAuthority('collection.legal')")
	@Transactional
	LegalActionDto charge(@PathVariable long actionId, @Valid @RequestBody CostRequest body) {
		AccountRef account = access.visible(service.action(actionId).accountId());
		service.charge(actionId, body.costType(), Money.won(body.amount()), body.incurredOn(), body.memo());
		return dto(service.action(actionId), account.accountNo());
	}

	/** 법조치 현황 (담당 범위 안). openOnly면 종결·취하 등 끝난 건 제외 */
	@GetMapping("/legal-actions")
	@PreAuthorize("hasAuthority('loan.read')")
	@Transactional(readOnly = true)
	List<LegalActionDto> recent(@RequestParam(defaultValue = "false") boolean openOnly) {
		DataScope scope = staff.loanScope();
		List<LegalActionDto> result = new java.util.ArrayList<>();
		for (LegalAction a : service.recent()) {
			if (openOnly && LegalActionRules.TERMINAL.contains(a.status())) {
				continue;
			}
			AccountRef account = accounts.get(a.accountId());
			if (scope.allows(account.assignedStaffId())) {
				result.add(dto(a, account.accountNo()));
			}
		}
		return result;
	}

	private LegalActionDto dto(LegalAction a, String accountNo) {
		List<StageDto> stages = service.stages(a).stream().map(s -> new StageDto(s.getId(), s.stage().name(),
				s.stageDate(), s.memo(), s.limitationEventId(), s.createdBy())).toList();
		List<CostDto> costs = service.costs(a).stream().map(c -> new CostDto(c.getId(), c.costType().name(),
				c.amount().won(), c.incurredOn(), c.chargedOn(), c.memo())).toList();
		long total = costs.stream().mapToLong(CostDto::amount).sum();
		return new LegalActionDto(a.getId(), a.actionNo(), a.accountId(), accountNo, a.type().name(),
				a.status().name(), a.courtName(), a.caseRef(), a.targetDesc(), a.claimAmount(), a.filedOn(),
				a.closedOn(), a.memo(), total,
				LegalActionRules.nextStages(a.type(), a.status()).stream().map(Enum::name).toList(), stages, costs);
	}
}
