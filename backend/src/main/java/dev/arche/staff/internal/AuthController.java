package dev.arche.staff.internal;

import dev.arche.common.BusinessException;
import dev.arche.staff.CurrentStaff;
import dev.arche.staff.Permission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인·비밀번호 변경 (정책서 7장).
 */
@RestController
@RequestMapping("/api/auth")
class AuthController {

	record LoginRequest(@NotBlank String loginId, @NotBlank String password) {
	}

	record PasswordChangeRequest(@NotBlank String currentPassword, @NotBlank @Size(min = 10) String newPassword) {
	}

	record Me(long id, String loginId, String name, String role, String roleLabel, Long teamId,
			List<String> permissions, boolean passwordChangeRequired, long passwordExpiresInDays) {
	}

	private final LoginService login;
	private final SecurityContextRepository contextRepository;
	private final SessionAuthenticationStrategy sessionStrategy;

	AuthController(LoginService login, SecurityContextRepository contextRepository,
			SessionAuthenticationStrategy loginSessionStrategy) {
		this.login = login;
		this.contextRepository = contextRepository;
		this.sessionStrategy = loginSessionStrategy;
	}

	/** SPA가 CSRF 쿠키를 받기 위한 호출 */
	@GetMapping("/csrf")
	CsrfToken csrf(CsrfToken token) {
		return token;
	}

	@PostMapping("/login")
	Me login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
		LoginService.Result result = login.authenticate(body.loginId(), body.password());
		Authentication auth = result.principal().toAuthentication();
		sessionStrategy.onAuthentication(auth, request, response);
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(auth);
		SecurityContextHolder.setContext(context);
		contextRepository.saveContext(context, request, response);
		return me(result.principal(), result.passwordExpiresInDays());
	}

	@GetMapping("/me")
	Me me(@AuthenticationPrincipal StaffPrincipal principal) {
		return me(principal, login.passwordExpiresInDays(principal.staff().id()));
	}

	@PostMapping("/password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void changePassword(@AuthenticationPrincipal StaffPrincipal principal,
			@Valid @RequestBody PasswordChangeRequest body, HttpServletRequest request,
			HttpServletResponse response) {
		StaffPrincipal updated = login.changePassword(principal.staff().id(), body.currentPassword(),
				body.newPassword());
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(updated.toAuthentication());
		SecurityContextHolder.setContext(context);
		contextRepository.saveContext(context, request, response);
	}

	private static Me me(StaffPrincipal p, long expiresInDays) {
		CurrentStaff s = p.staff();
		return new Me(s.id(), s.loginId(), s.name(), s.role().name(), s.role().label(), s.teamId(),
				Permission.of(s.role()).stream().map(Permission::code).toList(), p.passwordChangeRequired(),
				expiresInDays);
	}
}

@Service
class LoginService {

	record Result(StaffPrincipal principal, long passwordExpiresInDays) {
	}

	private final StaffRepository staff;
	private final PasswordEncoder encoder;
	private final DefaultAccessLogger accessLog;
	private final Clock clock;

	LoginService(StaffRepository staff, PasswordEncoder encoder, DefaultAccessLogger accessLog, Clock clock) {
		this.staff = staff;
		this.encoder = encoder;
		this.accessLog = accessLog;
		this.clock = clock;
	}

	@Transactional(noRollbackFor = BusinessException.class)
	Result authenticate(String loginId, String password) {
		Staff s = staff.findByLoginId(loginId).orElse(null);
		if (s == null || !s.enabled()) {
			accessLog.write(null, loginId, "LOGIN_FAILED", "STAFF", loginId, "unknown");
			throw new BusinessException("LOGIN_FAILED", "아이디 또는 비밀번호가 올바르지 않습니다");
		}
		if (s.locked()) {
			accessLog.write(s.getId(), loginId, "LOGIN_LOCKED", "STAFF", loginId, null);
			throw new BusinessException("ACCOUNT_LOCKED", "로그인 5회 실패로 잠긴 계정입니다. 관리자에게 해제를 요청하세요");
		}
		if (!encoder.matches(password, s.passwordHash())) {
			s.recordFailure();
			accessLog.write(s.getId(), loginId, "LOGIN_FAILED", "STAFF", loginId, "attempt " + s.failedAttempts());
			if (s.locked()) {
				throw new BusinessException("ACCOUNT_LOCKED", "로그인 5회 실패로 계정이 잠겼습니다. 관리자에게 해제를 요청하세요");
			}
			throw new BusinessException("LOGIN_FAILED", "아이디 또는 비밀번호가 올바르지 않습니다");
		}
		s.recordSuccess();
		accessLog.write(s.getId(), loginId, "LOGIN", "STAFF", loginId, null);
		LocalDate today = LocalDate.now(clock);
		return new Result(new StaffPrincipal(s.toCurrent(), s.passwordChangeRequired(today)),
				s.daysUntilPasswordExpiry(today));
	}

	@Transactional(readOnly = true)
	long passwordExpiresInDays(long staffId) {
		return staff.findById(staffId).orElseThrow().daysUntilPasswordExpiry(LocalDate.now(clock));
	}

	@Transactional
	StaffPrincipal changePassword(long staffId, String current, String next) {
		Staff s = staff.findById(staffId).orElseThrow();
		if (!encoder.matches(current, s.passwordHash())) {
			throw new BusinessException("PASSWORD_MISMATCH", "현재 비밀번호가 올바르지 않습니다");
		}
		if (encoder.matches(next, s.passwordHash())) {
			throw new BusinessException("PASSWORD_REUSED", "이전과 다른 비밀번호를 사용하세요");
		}
		s.changePassword(encoder.encode(next), LocalDate.now(clock));
		accessLog.write(s.getId(), s.loginId(), "PASSWORD_CHANGED", "STAFF", s.loginId(), null);
		return new StaffPrincipal(s.toCurrent(), false);
	}
}
