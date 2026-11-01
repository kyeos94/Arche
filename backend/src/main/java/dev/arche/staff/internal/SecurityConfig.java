package dev.arche.staff.internal;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.ConcurrentSessionControlAuthenticationStrategy;
import org.springframework.security.web.authentication.session.RegisterSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.session.HttpSessionEventPublisher;

/**
 * 세션 기반 인증 (서블릿 세션, ADR 0001). SPA는 쿠키 CSRF 토큰을 헤더로 돌려보낸다.
 * 화면 숨김은 편의 기능일 뿐, 실제 통제는 URL 인증 + 메서드 보안({@code @PreAuthorize})이다.
 */
@Configuration
@EnableMethodSecurity
class SecurityConfig {

	@Bean
	SecurityFilterChain api(HttpSecurity http, SessionRegistry sessionRegistry) throws Exception {
		http
				.csrf(csrf -> csrf.spa().ignoringRequestMatchers("/api/integration/**"))
				.securityContext(c -> c.securityContextRepository(securityContextRepository()))
				// 권한 관리에서 바꾼 권한을 로그인 중인 세션에도 바로 반영 (R69)
				.addFilterAfter(new AuthorityRefreshFilter(securityContextRepository()),
						org.springframework.security.web.context.SecurityContextHolderFilter.class)
				.sessionManagement(s -> s.maximumSessions(1).sessionRegistry(sessionRegistry)
						.expiredSessionStrategy(event -> {
							event.getResponse().setStatus(HttpServletResponse.SC_UNAUTHORIZED);
							event.getResponse().setContentType("application/json;charset=UTF-8");
							event.getResponse().getWriter()
									.write("{\"code\":\"SESSION_EXPIRED\",\"message\":\"다른 곳에서 로그인해 세션이 종료되었습니다\"}");
						}))
				.authorizeHttpRequests(a -> a
						.requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
						.requestMatchers("/api/integration/**").permitAll()
						.requestMatchers("/actuator/health").permitAll()
						.requestMatchers("/api/auth/**").authenticated()
						// 비밀번호 변경이 필요한 세션은 /api/auth/** 외에는 쓸 수 없다
						.requestMatchers("/api/**").access((auth, ctx) -> new AuthorizationDecision(
								auth.get() != null && auth.get().getPrincipal() instanceof StaffPrincipal p
										&& !p.passwordChangeRequired()))
						.anyRequest().permitAll())
				.exceptionHandling(e -> e
						.authenticationEntryPoint((req, res, ex) -> json(res, HttpServletResponse.SC_UNAUTHORIZED,
								"UNAUTHENTICATED", "로그인이 필요합니다"))
						.accessDeniedHandler((req, res, ex) -> json(res, HttpServletResponse.SC_FORBIDDEN,
								"FORBIDDEN", "권한이 없습니다")))
				.formLogin(f -> f.disable())
				.httpBasic(b -> b.disable())
				.logout(l -> l.logoutUrl("/api/auth/logout")
						.logoutSuccessHandler((req, res, auth) -> res.setStatus(HttpServletResponse.SC_NO_CONTENT)));
		return http.build();
	}

	private static void json(HttpServletResponse res, int status, String code, String message)
			throws java.io.IOException {
		res.setStatus(status);
		res.setContentType("application/json;charset=UTF-8");
		res.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
	}

	@Bean
	SecurityContextRepository securityContextRepository() {
		return new HttpSessionSecurityContextRepository();
	}

	@Bean
	SessionRegistry sessionRegistry() {
		return new SessionRegistryImpl();
	}

	/** 동시 로그인 1개: 새 로그인이 이전 세션을 만료시킨다 (정책서 7장) */
	@Bean
	SessionAuthenticationStrategy loginSessionStrategy(SessionRegistry registry) {
		ConcurrentSessionControlAuthenticationStrategy concurrent = new ConcurrentSessionControlAuthenticationStrategy(
				registry);
		concurrent.setMaximumSessions(1);
		return new CompositeSessionAuthenticationStrategy(List.of(concurrent,
				new ChangeSessionIdAuthenticationStrategy(), new RegisterSessionAuthenticationStrategy(registry)));
	}

	@Bean
	HttpSessionEventPublisher httpSessionEventPublisher() {
		return new HttpSessionEventPublisher();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}
}
