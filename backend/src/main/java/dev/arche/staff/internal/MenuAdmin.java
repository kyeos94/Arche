package dev.arche.staff.internal;

import dev.arche.common.BusinessException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 메뉴 관리 (R70). 대분류와 그 아래 메뉴(화면 경로·이름·순서)만 저장한다. 어떤 화면이 있는지와 화면마다 필요한 권한은
 * 프런트 코드의 화면 목록이 정하고, 메뉴가 보이는지는 그 권한(R69 권한표)으로 판단한다. 메뉴에서 빼도 화면 자체가 없어지지는 않는다.
 */
@Service
class MenuAdminService {

	/** 메뉴에서 뺄 수 없는 화면: 빼면 메뉴·권한을 되돌릴 길이 없어진다 */
	static final Set<String> REQUIRED_PATHS = Set.of("/admin/menus", "/admin/permissions");

	static final int MAX_GROUPS = 12;
	static final int MAX_ITEMS = 60;
	private static final Pattern PATH = Pattern.compile("/[a-z0-9][a-z0-9/-]{0,58}");

	record Item(String path, String label) {
	}

	record Group(Long id, String label, List<Item> items) {

		Group withoutItems() {
			return new Group(id, label, List.of());
		}
	}

	private final JdbcClient jdbc;
	private final DefaultAccessLogger accessLog;

	MenuAdminService(JdbcClient jdbc, DefaultAccessLogger accessLog) {
		this.jdbc = jdbc;
		this.accessLog = accessLog;
	}

	@Transactional(readOnly = true)
	List<Group> menu() {
		Map<Long, Group> groups = new LinkedHashMap<>();
		jdbc.sql("select id, label from menu_group order by sort_order, id").query((rs, n) -> {
			long id = rs.getLong("id");
			groups.put(id, new Group(id, rs.getString("label"), new ArrayList<>()));
			return null;
		}).list();
		jdbc.sql("select group_id, path, label from menu_item order by sort_order, id").query((rs, n) -> {
			Group g = groups.get(rs.getLong("group_id"));
			if (g != null) {
				g.items().add(new Item(rs.getString("path"), rs.getString("label")));
			}
			return null;
		}).list();
		return List.copyOf(groups.values());
	}

	/** 메뉴 전체를 받은 순서대로 교체한다 (대분류·메뉴 추가·삭제·이름·순서·이동을 한 번에) */
	@Transactional
	List<Group> replace(List<Group> groups, String reason) {
		String why = PermissionAdminService.requireReason(reason);
		validate(groups);
		jdbc.sql("delete from menu_item").update();
		jdbc.sql("delete from menu_group").update();
		int gOrder = 0;
		for (Group g : groups) {
			long groupId = jdbc.sql("insert into menu_group (label, sort_order) values (:l, :o) returning id")
					.param("l", g.label().trim()).param("o", ++gOrder).query(Long.class).single();
			int iOrder = 0;
			for (Item i : g.items()) {
				jdbc.sql("insert into menu_item (group_id, path, label, sort_order) values (:g, :p, :l, :o)")
						.param("g", groupId).param("p", i.path()).param("l", i.label().trim()).param("o", ++iOrder)
						.update();
			}
		}
		int items = groups.stream().mapToInt(g -> g.items().size()).sum();
		accessLog.log("MENU_CHANGED", "MENU", null, "대분류 " + groups.size() + "개, 메뉴 " + items + "개 · " + why);
		return menu();
	}

	static void validate(List<Group> groups) {
		if (groups == null || groups.isEmpty()) {
			throw new BusinessException("MENU_INVALID", "대분류가 하나 이상 있어야 합니다");
		}
		if (groups.size() > MAX_GROUPS) {
			throw new BusinessException("MENU_INVALID", "대분류는 " + MAX_GROUPS + "개까지입니다");
		}
		Set<String> labels = new HashSet<>();
		Set<String> paths = new HashSet<>();
		for (Group g : groups) {
			String label = label(g.label(), "대분류 이름");
			if (!labels.add(label)) {
				throw new BusinessException("MENU_INVALID", "대분류 이름이 겹칩니다: " + label);
			}
			if (g.items() == null) {
				throw new BusinessException("MENU_INVALID", "메뉴 목록이 없습니다: " + label);
			}
			for (Item i : g.items()) {
				label(i.label(), "메뉴 이름");
				if (i.path() == null || !PATH.matcher(i.path()).matches()) {
					throw new BusinessException("MENU_INVALID", "화면 경로 형식이 올바르지 않습니다: " + i.path());
				}
				if (!paths.add(i.path())) {
					throw new BusinessException("MENU_INVALID", "같은 화면을 두 번 넣을 수 없습니다: " + i.label());
				}
			}
		}
		if (paths.size() > MAX_ITEMS) {
			throw new BusinessException("MENU_INVALID", "메뉴는 " + MAX_ITEMS + "개까지입니다");
		}
		for (String required : REQUIRED_PATHS) {
			if (!paths.contains(required)) {
				throw new BusinessException("MENU_REQUIRED", "메뉴 관리·권한 관리 화면은 메뉴에서 뺄 수 없습니다");
			}
		}
	}

	private static String label(String value, String what) {
		String v = value == null ? "" : value.trim();
		if (v.isEmpty() || v.length() > 20) {
			throw new BusinessException("MENU_INVALID", what + "은 1~20자로 입력하세요");
		}
		return v;
	}
}
