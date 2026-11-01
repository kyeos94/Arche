package dev.arche.staff.internal;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 메뉴 (R70): 조회는 로그인한 모든 직원, 변경은 시스템관리자 */
@RestController
class MenuController {

	record MenuUpdate(List<MenuAdminService.Group> groups, String reason) {
	}

	private final MenuAdminService menus;

	MenuController(MenuAdminService menus) {
		this.menus = menus;
	}

	@GetMapping("/api/menus")
	List<MenuAdminService.Group> menu() {
		return menus.menu();
	}

	@PutMapping("/api/admin/menus")
	@PreAuthorize("hasAuthority('admin.manage')")
	List<MenuAdminService.Group> replace(@RequestBody MenuUpdate body) {
		return menus.replace(body.groups(), body.reason());
	}
}

/** 권한 관리 (R69): 시스템관리자 */
@RestController
@PreAuthorize("hasAuthority('admin.manage')")
class PermissionAdminController {

	record CellChange(String role, String permission, boolean granted, String reason) {
	}

	record Reset(String reason) {
	}

	private final PermissionAdminService permissions;

	PermissionAdminController(PermissionAdminService permissions) {
		this.permissions = permissions;
	}

	@GetMapping("/api/admin/permissions")
	PermissionAdminService.Matrix matrix() {
		return permissions.matrix();
	}

	@PostMapping("/api/admin/permissions")
	PermissionAdminService.Matrix change(@RequestBody CellChange body) {
		permissions.set(body.role(), body.permission(), body.granted(), body.reason());
		return permissions.matrix();
	}

	@PostMapping("/api/admin/permissions/reset")
	@ResponseStatus(HttpStatus.OK)
	PermissionAdminService.Matrix reset(@RequestBody Reset body) {
		permissions.resetAll(body.reason());
		return permissions.matrix();
	}

	@GetMapping("/api/admin/permissions/changes")
	List<PermissionAdminService.Change> changes(@RequestParam(defaultValue = "100") int limit) {
		return permissions.changes(limit);
	}
}
