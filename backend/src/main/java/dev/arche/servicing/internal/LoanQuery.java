package dev.arche.servicing.internal;

import dev.arche.customer.Customers;
import dev.arche.servicing.LoanFilter;
import dev.arche.staff.DataScope;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 계약 목록 검색 SQL (ADR 0011). 값이 있는 조건만 이름 붙은 파라미터로 붙인다(null 파라미터 타입 문제도 피함).
 * 정렬 칸은 허용 목록으로만 받아 SQL에 넣는다.
 */
@Component
class LoanQuery {

	/** 화면 정렬 키 → 칼럼 (허용 목록) */
	static final Map<String, String> SORT_COLUMNS = Map.of("accountNo", "account_no", "productCode", "product_code",
			"status", "status", "principalBalance", "principal_balance", "payoffAmount", "payoff_amount",
			"delinquencyDays", "delinquency_days", "nextDueDate", "next_due_date", "disbursedOn", "disbursed_on",
			"initialPrincipal", "initial_principal");

	static final List<Integer> PAGE_SIZES = List.of(10, 20, 50, 100, 200);

	record Row(long id, String accountNo, long customerId, String productCode, String status, long initialPrincipal,
			long principalBalance, long payoffAmount, int delinquencyDays, @Nullable LocalDate nextDueDate,
			@Nullable LocalDate disbursedOn, @Nullable Long assignedStaffId) {
	}

	record Page(List<Row> rows, long total) {
	}

	/** where 절(앞에 and를 붙여 쓰는 조각)과 파라미터. 결과가 없을 게 확실하면 empty */
	record Where(String sql, Map<String, Object> params) {
	}

	private static final String COLUMNS = """
			id, account_no, customer_id, product_code, status, initial_principal, principal_balance, payoff_amount,
			delinquency_days, next_due_date, disbursed_on, assigned_staff_id
			""";

	private final JdbcClient jdbc;
	private final Customers customers;

	LoanQuery(JdbcClient jdbc, Customers customers) {
		this.jdbc = jdbc;
		this.customers = customers;
	}

	Optional<Where> where(LoanFilter f, DataScope scope) {
		StringBuilder sql = new StringBuilder();
		Map<String, Object> p = new HashMap<>();
		if (scope.kind() != DataScope.Kind.ALL) {
			Set<Long> ids = scope.staffIds();
			if (ids == null || ids.isEmpty()) {
				return Optional.empty();
			}
			sql.append(" and assigned_staff_id in (:scopeStaff)");
			p.put("scopeStaff", ids);
		}
		if (has(f.status())) {
			sql.append(" and status = :status");
			p.put("status", f.status().trim());
		}
		if (has(f.productCode())) {
			sql.append(" and product_code = :product");
			p.put("product", f.productCode().trim());
		}
		if (f.unassigned()) {
			sql.append(" and assigned_staff_id is null");
		} else if (f.assignedStaffId() != null) {
			sql.append(" and assigned_staff_id = :assignee");
			p.put("assignee", f.assignedStaffId());
		}
		range(sql, p, "principal_balance", "principal", f.principalMin(), f.principalMax());
		range(sql, p, "payoff_amount", "payoff", f.payoffMin(), f.payoffMax());
		range(sql, p, "delinquency_days", "delinquency", f.delinquencyMin(), f.delinquencyMax());
		range(sql, p, "next_due_date", "nextDue", f.nextDueFrom(), f.nextDueTo());
		range(sql, p, "disbursed_on", "disbursed", f.disbursedFrom(), f.disbursedTo());
		if (has(f.keyword())) {
			String k = f.keyword().trim();
			String type = f.keywordType() == null ? "AUTO" : f.keywordType();
			if ("AUTO".equals(type)) {
				type = k.matches("[0-9]{6}-?[0-9]{7}") ? "RESIDENT_NO" : "ACCOUNT_NO";
			}
			switch (type) {
				case "ACCOUNT_NO" -> {
					sql.append(" and account_no like :accountNo");
					p.put("accountNo", "%" + escapeLike(k) + "%");
				}
				case "NAME", "RESIDENT_NO", "PHONE" -> {
					List<Long> ids = customers.idsMatching(Customers.SearchKind.valueOf(type), k);
					if (ids.isEmpty()) {
						return Optional.empty();
					}
					sql.append(" and customer_id in (:customers)");
					p.put("customers", ids);
				}
				default -> throw new dev.arche.common.BusinessException("KEYWORD_TYPE", "검색 항목을 확인하세요");
			}
		}
		return Optional.of(new Where(sql.toString(), p));
	}

	Page page(LoanFilter f, DataScope scope, int page, int size, @Nullable String sortKey, boolean desc) {
		Optional<Where> w = where(f, scope);
		if (w.isEmpty()) {
			return new Page(List.of(), 0);
		}
		int pageSize = PAGE_SIZES.contains(size) ? size : 50;
		String column = sortKey == null ? null : SORT_COLUMNS.get(sortKey);
		// 같은 값이면 최신 계약이 먼저 (페이지가 바뀌어도 순서가 흔들리지 않게 id로 마무리)
		String order = column == null ? "id desc"
				: column + (desc ? " desc nulls last" : " asc nulls last") + ", id desc";
		long total = jdbc.sql("select count(*) from loan_account where true" + w.get().sql())
				.params(w.get().params()).query(Long.class).single();
		List<Row> rows = jdbc.sql("select " + COLUMNS + " from loan_account where true" + w.get().sql() + " order by "
				+ order + " limit :limit offset :offset").params(w.get().params()).param("limit", pageSize)
				.param("offset", (long) Math.max(page, 0) * pageSize).query(LoanQuery::row).list();
		return new Page(rows, total);
	}

	/** 엑셀 다운로드: 같은 조건으로 id 역순으로 나눠 읽는다 */
	void forEach(LoanFilter f, DataScope scope, int chunk, java.util.function.Consumer<Row> consumer) {
		Optional<Where> w = where(f, scope);
		if (w.isEmpty()) {
			return;
		}
		long after = Long.MAX_VALUE;
		while (true) {
			List<Row> rows = jdbc.sql("select " + COLUMNS + " from loan_account where id < :after" + w.get().sql()
					+ " order by id desc limit :limit").params(w.get().params()).param("after", after)
					.param("limit", chunk).query(LoanQuery::row).list();
			rows.forEach(consumer);
			if (rows.size() < chunk) {
				return;
			}
			after = rows.getLast().id();
		}
	}

	private static Row row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
		return new Row(rs.getLong("id"), rs.getString("account_no"), rs.getLong("customer_id"),
				rs.getString("product_code"), rs.getString("status"), rs.getLong("initial_principal"),
				rs.getLong("principal_balance"), rs.getLong("payoff_amount"), rs.getInt("delinquency_days"),
				rs.getObject("next_due_date", LocalDate.class), rs.getObject("disbursed_on", LocalDate.class),
				rs.getObject("assigned_staff_id", Long.class));
	}

	private static void range(StringBuilder sql, Map<String, Object> p, String column, String name,
			@Nullable Object min, @Nullable Object max) {
		if (min != null) {
			sql.append(" and ").append(column).append(" >= :").append(name).append("Min");
			p.put(name + "Min", min);
		}
		if (max != null) {
			sql.append(" and ").append(column).append(" <= :").append(name).append("Max");
			p.put(name + "Max", max);
		}
	}

	private static boolean has(@Nullable String s) {
		return s != null && !s.isBlank();
	}

	/** LIKE의 % _ \ 는 글자 그대로 찾는다 */
	static String escapeLike(String s) {
		return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

}
