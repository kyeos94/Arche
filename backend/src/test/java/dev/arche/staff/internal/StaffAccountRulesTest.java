package dev.arche.staff.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.arche.staff.Role;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 직원 등록·퇴사 규칙 (R75) */
class StaffAccountRulesTest {

	@Test
	void 퇴사_불가_조건() {
		assertThatCode(() -> StaffAccountService.check(1, 2, Role.COLLECTOR, true, 0)).doesNotThrowAnyException();
		assertThatThrownBy(() -> StaffAccountService.check(1, 1, Role.COLLECTOR, true, 1))
				.hasMessageContaining("자기 자신");
		assertThatThrownBy(() -> StaffAccountService.check(1, 2, Role.DEMO_ALL, true, 1)).hasMessageContaining("데모");
		assertThatThrownBy(() -> StaffAccountService.check(1, 2, Role.TELLER, false, 1))
				.hasMessageContaining("이미 퇴사");
		assertThatThrownBy(() -> StaffAccountService.check(1, 2, Role.SYSTEM_ADMIN, true, 0))
				.hasMessageContaining("한 명은 남아");
		assertThatCode(() -> StaffAccountService.check(1, 2, Role.SYSTEM_ADMIN, true, 1)).doesNotThrowAnyException();
	}

	@Test
	void 로그인_ID_형식() {
		assertThat(StaffAccountService.LOGIN_ID.matcher("kim.collector").matches()).isTrue();
		assertThat(StaffAccountService.LOGIN_ID.matcher("u01").matches()).isTrue();
		assertThat(StaffAccountService.LOGIN_ID.matcher("1kim").matches()).isFalse();
		assertThat(StaffAccountService.LOGIN_ID.matcher("ab").matches()).isFalse();
		assertThat(StaffAccountService.LOGIN_ID.matcher("Kim").matches()).isFalse();
		assertThat(StaffAccountService.LOGIN_ID.matcher("kim lee").matches()).isFalse();
	}

	@Test
	void 임시_비밀번호는_12자이고_소문자_대문자_숫자_기호를_섞고_헷갈리는_글자는_없다() {
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 500; i++) {
			String p = StaffAccountService.temporaryPassword();
			assertThat(p).hasSize(12).containsPattern("[a-z]").containsPattern("[A-Z]").containsPattern("[0-9]")
					.containsPattern("[!@#$%*]").doesNotContainPattern("[0O1lI]");
			seen.add(p);
		}
		assertThat(seen).hasSize(500);
	}
}
