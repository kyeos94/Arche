package dev.arche.staff.internal;

import dev.arche.common.BusinessException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 직원 관리 (시스템관리자). 잠금 해제, 비밀번호 초기화, 접근 기록 조회.
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasAuthority('admin.manage')")
class StaffAdminController {

	record StaffRow(long id, String loginId, String name, String role, String roleLabel, Long teamId,
			boolean locked, int failedAttempts, boolean enabled, OffsetDateTime resignedAt) {
	}

	record CreateRequest(String loginId, String name, String role, Long teamId) {
	}

	record ReasonRequest(String reason) {
	}

	record ResetRequest(String temporaryPassword) {
	}

	record AccessLogRow(Long staffId, String loginId, OffsetDateTime accessedAt, String ip, String action,
			String targetType, String targetId, String reason) {
	}

	private final StaffRepository staff;
	private final AccessLogRepository logs;
	private final PasswordEncoder encoder;
	private final DefaultAccessLogger accessLog;
	private final StaffAccountService accounts;
	private final Clock clock;

	StaffAdminController(StaffRepository staff, AccessLogRepository logs, PasswordEncoder encoder,
			DefaultAccessLogger accessLog, StaffAccountService accounts, Clock clock) {
		this.accounts = accounts;
		this.staff = staff;
		this.logs = logs;
		this.encoder = encoder;
		this.accessLog = accessLog;
		this.clock = clock;
	}

	@GetMapping("/staff")
	@Transactional(readOnly = true)
	List<StaffRow> list() {
		return staff.findAll().stream().map(s -> new StaffRow(s.getId(), s.loginId(), s.name(), s.role().name(),
				s.role().label(), s.teamId(), s.locked(), s.failedAttempts(), s.enabled(), s.resignedAt())).toList();
	}

	/** 직원 등록 (R75). 임시 비밀번호는 이 응답에서 한 번만 보여 준다 */
	@PostMapping("/staff")
	StaffAccountService.Created create(@RequestBody CreateRequest r) {
		return accounts.create(r.loginId(), r.name(), r.role(), r.teamId());
	}

	/** 복직 (R75). 새 임시 비밀번호를 발급한다 */
	@PostMapping("/staff/{id}/reinstatement")
	StaffAccountService.Created reinstate(@PathVariable long id, @RequestBody ReasonRequest r) {
		return accounts.reinstate(id, r.reason());
	}

	@GetMapping("/staff/status-changes")
	List<StaffAccountService.StatusChange> statusChanges() {
		return accounts.changes();
	}

	@PostMapping("/staff/{id}/unlock")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Transactional
	void unlock(@PathVariable long id) {
		Staff s = find(id);
		s.unlock();
		accessLog.log("STAFF_UNLOCK", "STAFF", s.loginId(), null);
	}

	@PostMapping("/staff/{id}/reset-password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Transactional
	void resetPassword(@PathVariable long id, @RequestBody ResetRequest body) {
		if (body.temporaryPassword() == null || body.temporaryPassword().length() < 10) {
			throw new BusinessException("PASSWORD_POLICY", "임시 비밀번호는 10자 이상이어야 합니다");
		}
		Staff s = find(id);
		s.resetPassword(encoder.encode(body.temporaryPassword()), LocalDate.now(clock));
		accessLog.log("STAFF_PASSWORD_RESET", "STAFF", s.loginId(), null);
	}

	@GetMapping("/access-logs")
	@Transactional(readOnly = true)
	List<AccessLogRow> accessLogs(@RequestParam(defaultValue = "0") int page) {
		return logs.findAllByOrderByIdDesc(PageRequest.of(page, 100)).map(l -> new AccessLogRow(l.staffId(),
				l.loginId(), l.accessedAt(), l.ip(), l.action(), l.targetType(), l.targetId(), l.reason()))
				.getContent();
	}

	private Staff find(long id) {
		return staff.findById(id).orElseThrow(() -> new BusinessException("STAFF_NOT_FOUND", "직원이 없습니다"));
	}
}
