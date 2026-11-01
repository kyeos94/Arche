package dev.arche.staff.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.arche.staff.Role;
import org.junit.jupiter.api.Test;

/** 직원 역할 변경 가능 여부 (R71) */
class StaffRoleRulesTest {

	@Test
	void 자기_역할은_바꿀_수_없다() {
		assertThatThrownBy(() -> StaffRoleService.check(1, 1, Role.SYSTEM_ADMIN, Role.HEAD, true, 3))
				.hasMessageContaining("자기 역할");
	}

	@Test
	void 데모_전용_역할은_주거나_바꿀_수_없다() {
		assertThatThrownBy(() -> StaffRoleService.check(1, 2, Role.TELLER, Role.DEMO_ALL, true, 1))
				.hasMessageContaining("데모");
		assertThatThrownBy(() -> StaffRoleService.check(1, 2, Role.DEMO_ALL, Role.TELLER, true, 1))
				.hasMessageContaining("데모");
		assertThat(StaffRoleService.assignableRoles()).doesNotContain(Role.DEMO_ALL).hasSize(7);
	}

	@Test
	void 팀은_필수() {
		assertThatThrownBy(() -> StaffRoleService.check(1, 2, Role.COLLECTOR, Role.COLLECTION_LEAD, false, 1))
				.hasMessageContaining("팀");
	}

	@Test
	void 마지막_시스템관리자는_다른_역할로_바꿀_수_없다() {
		assertThatThrownBy(() -> StaffRoleService.check(1, 2, Role.SYSTEM_ADMIN, Role.HEAD, true, 0))
				.hasMessageContaining("한 명은");
		assertThatCode(() -> StaffRoleService.check(1, 2, Role.SYSTEM_ADMIN, Role.HEAD, true, 1))
				.doesNotThrowAnyException();
		assertThatCode(() -> StaffRoleService.check(1, 2, Role.COLLECTOR, Role.COLLECTION_LEAD, true, 0))
				.doesNotThrowAnyException();
	}
}
