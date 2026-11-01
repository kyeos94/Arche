package dev.arche.operation.internal;

import dev.arche.collection.AlertAssignments;
import dev.arche.common.BusinessException;
import dev.arche.origination.UnderwriterAssignments;
import dev.arche.servicing.LoanAccounts;
import dev.arche.staff.CurrentStaff;
import dev.arche.staff.Role;
import dev.arche.staff.StaffAccounts;
import dev.arche.staff.StaffDirectory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 퇴사 처리와 담당 건 이관 (R75, 가정·검수 필요). 직원·계약·신청·추심 알림이 서로 다른 모듈이라 여기서 한 트랜잭션으로 묶는다:
 * 하나라도 실패하면(예: 받을 심사역의 담당 상품이 아님) 퇴사와 이관 모두 되돌아간다.
 * <ul>
 * <li>완제 전 담당 계약과 읽지 않은 추심 알림 → 계약 후임(재직 중인 채권담당·채권팀장)</li>
 * <li>배정·심사 중·승인·약정 상태 신청 → 심사 후임(재직 중인 심사역·심사팀장, 담당 상품·부재 규칙은 수동 재배정과 같음)</li>
 * <li>넘길 건이 있는데 후임을 고르지 않으면 퇴사 처리하지 않는다</li>
 * </ul>
 */
@Service
class StaffOffboardingService {

	static final List<Role> LOAN_SUCCESSOR_ROLES = List.of(Role.COLLECTOR, Role.COLLECTION_LEAD);
	static final List<Role> APPLICATION_SUCCESSOR_ROLES = List.of(Role.UNDERWRITER, Role.UNDERWRITING_LEAD);

	record Candidate(long id, String name, String roleLabel, @Nullable String team) {
	}

	record Workload(long staffId, String name, String roleLabel, int loans, int alerts, int applications,
			List<Candidate> loanSuccessors, List<Candidate> applicationSuccessors) {
	}

	record ResignRequest(String reason, @Nullable Long loanSuccessorId, @Nullable Long applicationSuccessorId) {
	}

	record Result(long staffId, int loans, int alerts, int applications, @Nullable String handover) {
	}

	private final StaffAccounts accounts;
	private final StaffDirectory staff;
	private final LoanAccounts loans;
	private final UnderwriterAssignments applications;
	private final AlertAssignments alerts;

	StaffOffboardingService(StaffAccounts accounts, StaffDirectory staff, LoanAccounts loans,
			UnderwriterAssignments applications, AlertAssignments alerts) {
		this.accounts = accounts;
		this.staff = staff;
		this.loans = loans;
		this.applications = applications;
		this.alerts = alerts;
	}

	/** 퇴사 화면: 넘길 담당 건 수와 받을 수 있는 직원 */
	@Transactional(readOnly = true)
	Workload workload(long staffId) {
		CurrentStaff target = staff.byId(staffId)
				.orElseThrow(() -> new BusinessException("STAFF_NOT_FOUND", "직원이 없습니다"));
		return new Workload(target.id(), target.name(), target.role().label(), loans.countAssigned(staffId),
				alerts.openCount(staffId), applications.openCount(staffId), candidates(LOAN_SUCCESSOR_ROLES, staffId),
				candidates(APPLICATION_SUCCESSOR_ROLES, staffId));
	}

	@Transactional
	Result resign(long staffId, ResignRequest r) {
		CurrentStaff target = accounts.checkResignable(staffId);
		int loanCount = loans.countAssigned(staffId);
		int alertCount = alerts.openCount(staffId);
		int appCount = applications.openCount(staffId);
		List<String> handover = new ArrayList<>();
		if (loanCount + alertCount > 0) {
			CurrentStaff to = successor(r.loanSuccessorId(), staffId, LOAN_SUCCESSOR_ROLES,
					"담당 계약 " + loanCount + "건·추심 알림 " + alertCount + "건을 넘겨받을 채권 담당자를 고르세요");
			int moved = loans.reassignAssigned(staffId, to.id());
			int movedAlerts = alerts.reassignOpen(staffId, to.id());
			handover.add("계약 " + moved + "건·알림 " + movedAlerts + "건 → " + to.name());
		}
		if (appCount > 0) {
			if (r.applicationSuccessorId() == null) {
				throw new BusinessException("SUCCESSOR_REQUIRED", "진행 중 신청 " + appCount + "건을 넘겨받을 심사 직원을 고르세요");
			}
			CurrentStaff to = successor(r.applicationSuccessorId(), staffId, APPLICATION_SUCCESSOR_ROLES, "");
			int moved = applications.reassignOpen(staffId, to.id(), "퇴사 (" + target.name() + ")");
			handover.add("신청 " + moved + "건 → " + to.name());
		}
		String summary = handover.isEmpty() ? null : String.join(", ", handover);
		accounts.resign(staffId, r.reason(), summary);
		return new Result(staffId, loanCount, alertCount, appCount, summary);
	}

	private CurrentStaff successor(@Nullable Long id, long leaving, List<Role> roles, String missing) {
		if (id == null) {
			throw new BusinessException("SUCCESSOR_REQUIRED", missing);
		}
		if (id == leaving) {
			throw new BusinessException("SUCCESSOR_INVALID", "퇴사하는 직원에게 넘길 수 없습니다");
		}
		CurrentStaff to = accounts.requireActive(id);
		if (!roles.contains(to.role())) {
			throw new BusinessException("SUCCESSOR_ROLE", to.name() + "님은 " + roles.stream().map(Role::label)
					.reduce((a, b) -> a + "·" + b).orElse("") + "이 아니라 넘겨받을 수 없습니다");
		}
		return to;
	}

	private List<Candidate> candidates(List<Role> roles, long except) {
		Map<Long, String> teams = staff.teamNames();
		return roles.stream().flatMap(role -> staff.activeByRole(role).stream()).filter(s -> s.id() != except)
				.map(s -> new Candidate(s.id(), s.name(), s.role().label(),
						s.teamId() == null ? null : teams.get(s.teamId())))
				.toList();
	}
}

@RestController
@RequestMapping("/api/admin/staff/{id}")
@PreAuthorize("hasAuthority('admin.manage')")
class StaffOffboardingController {

	private final StaffOffboardingService service;

	StaffOffboardingController(StaffOffboardingService service) {
		this.service = service;
	}

	@GetMapping("/workload")
	StaffOffboardingService.Workload workload(@PathVariable long id) {
		return service.workload(id);
	}

	@PostMapping("/resignation")
	StaffOffboardingService.Result resign(@PathVariable long id,
			@RequestBody StaffOffboardingService.ResignRequest r) {
		return service.resign(id, r);
	}
}
