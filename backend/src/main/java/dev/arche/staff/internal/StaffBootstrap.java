package dev.arche.staff.internal;

import dev.arche.staff.Role;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 직원이 한 명도 없으면 초기 계정을 만든다.
 * <ul>
 * <li>데모(arche.demo.enabled=true): 역할마다 1명 + 모든 메뉴가 보이는 demo(DEMO_ALL), 비밀번호 arche.demo.password,
 * 첫 로그인 변경 생략</li>
 * <li>그 외: 시스템관리자 admin 1명, 비밀번호 arche.bootstrap.admin-password, 첫 로그인에서 변경</li>
 * </ul>
 */
@Component
@Order(0)
class StaffBootstrap implements ApplicationRunner {

	static final String DEMO_LOGIN_ID = "demo";

	private final StaffRepository staff;
	private final PasswordEncoder encoder;
	private final Clock clock;
	private final boolean demo;
	private final String demoPassword;
	private final String adminPassword;

	StaffBootstrap(StaffRepository staff, PasswordEncoder encoder, Clock clock,
			@Value("${arche.demo.enabled:false}") boolean demo,
			@Value("${arche.demo.password:}") String demoPassword,
			@Value("${arche.bootstrap.admin-password:}") String adminPassword) {
		this.staff = staff;
		this.encoder = encoder;
		this.clock = clock;
		this.demo = demo;
		this.demoPassword = demoPassword;
		this.adminPassword = adminPassword;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		LocalDate today = LocalDate.now(clock);
		if (demo && staff.findByLoginId(DEMO_LOGIN_ID).isEmpty()) {
			// 기존 데모 DB에도 추가되도록 직원 수와 관계없이 확인
			staff.save(new Staff(DEMO_LOGIN_ID, "데모(전체 메뉴)", Role.DEMO_ALL, 9L, encoder.encode(demoPassword), today,
					false));
		}
		if (staff.count() > (demo ? 1 : 0)) {
			return;
		}
		if (demo) {
			String hash = encoder.encode(demoPassword);
			staff.save(new Staff("uw", "김심사", Role.UNDERWRITER, 1L, hash, today, false));
			staff.save(new Staff("uwlead", "이심사팀장", Role.UNDERWRITING_LEAD, 1L, hash, today, false));
			staff.save(new Staff("col1", "박채권", Role.COLLECTOR, 2L, hash, today, false));
			staff.save(new Staff("col2", "최채권", Role.COLLECTOR, 3L, hash, today, false));
			staff.save(new Staff("collead", "정채권팀장", Role.COLLECTION_LEAD, 2L, hash, today, false));
			staff.save(new Staff("teller", "한출납", Role.TELLER, 4L, hash, today, false));
			staff.save(new Staff("head", "오본부장", Role.HEAD, 9L, hash, today, false));
			staff.save(new Staff("admin", "윤관리자", Role.SYSTEM_ADMIN, 9L, hash, today, false));
		} else if (!adminPassword.isBlank()) {
			staff.save(new Staff("admin", "시스템관리자", Role.SYSTEM_ADMIN, 9L, encoder.encode(adminPassword), today,
					true));
		}
	}
}
