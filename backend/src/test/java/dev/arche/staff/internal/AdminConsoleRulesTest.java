package dev.arche.staff.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.arche.common.BusinessException;
import dev.arche.staff.Permission;
import dev.arche.staff.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 권한 관리(R69)의 적용 방식과 메뉴 관리(R70)의 검증 */
class AdminConsoleRulesTest {

	@AfterEach
	void restore() {
		Permission.applyOverrides(Map.of());
	}

	@Test
	void 바꾼_권한이_정책서_기본값보다_우선한다() {
		assertThat(Permission.LOAN_READ.grantedTo(Role.TELLER)).isTrue();
		Permission.applyOverrides(Map.of(Permission.LOAN_READ, Map.of(Role.TELLER, false),
				Permission.EXCEL_DOWNLOAD, Map.of(Role.COLLECTOR, true)));
		assertThat(Permission.LOAN_READ.grantedTo(Role.TELLER)).isFalse();
		assertThat(Permission.LOAN_READ.grantedByDefault(Role.TELLER)).isTrue();
		assertThat(Permission.EXCEL_DOWNLOAD.grantedTo(Role.COLLECTOR)).isTrue();
		assertThat(Permission.of(Role.TELLER)).doesNotContain(Permission.LOAN_READ);
		// 데모 전용 역할은 항상 전체
		Permission.applyOverrides(Map.of(Permission.LOAN_READ, Map.of(Role.DEMO_ALL, false)));
		assertThat(Permission.LOAN_READ.grantedTo(Role.DEMO_ALL)).isTrue();
	}

	@Test
	void 시스템관리자의_관리_권한과_데모_역할은_잠겨_있다() {
		assertThat(PermissionAdminService.locked(Role.SYSTEM_ADMIN, Permission.ADMIN)).isTrue();
		assertThat(PermissionAdminService.locked(Role.DEMO_ALL, Permission.LOAN_READ)).isTrue();
		assertThat(PermissionAdminService.locked(Role.HEAD, Permission.ADMIN)).isFalse();
		assertThat(PermissionAdminService.editableRoles()).doesNotContain(Role.DEMO_ALL);
	}

	@Test
	void 모든_권한에_화면_이름이_있다() {
		assertThat(PermissionAdminService.LABELS.keySet()).containsExactlyInAnyOrder(Permission.values());
	}

	@Test
	void 변경_사유는_2자_이상() {
		assertThatThrownBy(() -> PermissionAdminService.requireReason(" 가 ")).isInstanceOf(BusinessException.class);
		assertThat(PermissionAdminService.requireReason(" 조직 개편 ")).isEqualTo("조직 개편");
	}

	private static List<MenuAdminService.Group> menu(String... extraPaths) {
		List<MenuAdminService.Item> items = new ArrayList<>(List.of(new MenuAdminService.Item("/admin/menus", "메뉴 관리"),
				new MenuAdminService.Item("/admin/permissions", "권한 관리")));
		for (String p : extraPaths) {
			items.add(new MenuAdminService.Item(p, "화면"));
		}
		return List.of(new MenuAdminService.Group(null, "시스템설정", items));
	}

	@Test
	void 메뉴_검증() {
		MenuAdminService.validate(menu("/loans"));
		assertThatThrownBy(() -> MenuAdminService.validate(List.of())).hasMessageContaining("대분류");
		assertThatThrownBy(() -> MenuAdminService.validate(menu("/loans", "/loans"))).hasMessageContaining("두 번");
		assertThatThrownBy(() -> MenuAdminService.validate(menu("https://example.invalid")))
				.hasMessageContaining("경로");
		assertThatThrownBy(() -> MenuAdminService.validate(List.of(new MenuAdminService.Group(null, "여신관리",
				List.of(new MenuAdminService.Item("/loans", "계약 목록")))))).hasMessageContaining("뺄 수 없습니다");
		assertThatThrownBy(() -> MenuAdminService.validate(List.of(menu().get(0), menu().get(0).withoutItems())))
				.hasMessageContaining("겹칩니다");
	}
}
