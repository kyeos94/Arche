package dev.arche.reporting.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 보고서 테이블 읽기·쓰기 (V16). 집계 결과를 한 번에 쓰는 추가 전용 성격이라 JPA 대신 JdbcClient (ADR 0009).
 * 트랜잭션은 호출하는 서비스가 연다.
 */
@Component
class ReportStore {

	record DailyHeader(LocalDate businessDate, OffsetDateTime createdAt, String createdBy, long journalDebit,
			long journalCredit, int unmappedCount) {
	}

	record MonthHeader(LocalDate month, LocalDate asOf, OffsetDateTime createdAt) {
	}

	record MonthRow(long accountId, String accountNo, String productCode, String status, Bucket bucket,
			int delinquencyDays, long principalBalance, long claimBalance) {
	}

	record Account(String code, String name, String category, int sortOrder) {
	}

	private final JdbcClient jdbc;

	ReportStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	// ------------------------------------------------------------------ 설정

	JournalMapper.Rules rules() {
		Map<String, JournalMapper.Rule> byType = new HashMap<>();
		jdbc.sql("select posting_type, mode, counter_account, book_account from journal_rule")
				.query((rs, n) -> new JournalMapper.Rule(rs.getString(1), JournalMapper.Mode.valueOf(rs.getString(2)),
						rs.getString(3), rs.getString(4)))
				.list().forEach(r -> byType.put(r.postingType(), r));
		Map<JournalMapper.Component, String> components = new EnumMap<>(JournalMapper.Component.class);
		jdbc.sql("select component, account_code from journal_component_account").query((rs, n) -> {
			for (JournalMapper.Component c : JournalMapper.Component.values()) {
				if (c.name().equals(rs.getString(1))) {
					components.put(c, rs.getString(2));
				}
			}
			return null;
		}).list();
		return new JournalMapper.Rules(byType, components);
	}

	List<Account> accounts() {
		return jdbc.sql("select code, name, category, sort_order from journal_account order by sort_order")
				.query((rs, n) -> new Account(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4))).list();
	}

	/** 기준일에 유효한 적립률 (R67) */
	Map<Bucket, BigDecimal> ratesAt(LocalDate date) {
		Map<Bucket, BigDecimal> rates = new EnumMap<>(Bucket.class);
		jdbc.sql("""
				select distinct on (bucket) bucket, rate from provision_rate
				where valid_from <= :d order by bucket, valid_from desc
				""").param("d", date).query((rs, n) -> {
			Bucket b = Bucket.parse(rs.getString(1));
			if (b != null) {
				rates.put(b, rs.getBigDecimal(2));
			}
			return null;
		}).list();
		return rates;
	}

	// ------------------------------------------------------------------ 일보

	void replaceDaily(DailyHeader h, List<DailyReportBuilder.Line> lines, List<JournalMapper.AccountTotal> totals) {
		jdbc.sql("delete from report_journal_line where business_date = :d").param("d", h.businessDate()).update();
		jdbc.sql("delete from report_daily_line where business_date = :d").param("d", h.businessDate()).update();
		jdbc.sql("delete from report_daily where business_date = :d").param("d", h.businessDate()).update();
		jdbc.sql("""
				insert into report_daily (business_date, created_at, created_by, journal_debit, journal_credit,
				  unmapped_count) values (:d, :at, :by, :debit, :credit, :unmapped)
				""").param("d", h.businessDate()).param("at", h.createdAt()).param("by", h.createdBy())
				.param("debit", h.journalDebit()).param("credit", h.journalCredit()).param("unmapped", h.unmappedCount())
				.update();
		for (DailyReportBuilder.Line l : lines) {
			jdbc.sql("""
					insert into report_daily_line (business_date, section, item, item_count, amount)
					values (:d, :s, :i, :c, :a)
					""").param("d", h.businessDate()).param("s", l.section()).param("i", l.item())
					.param("c", l.count()).param("a", l.amount()).update();
		}
		for (JournalMapper.AccountTotal t : totals) {
			jdbc.sql("""
					insert into report_journal_line (business_date, account_code, debit, credit)
					values (:d, :code, :debit, :credit)
					""").param("d", h.businessDate()).param("code", t.accountCode()).param("debit", t.debit())
					.param("credit", t.credit()).update();
		}
	}

	Optional<DailyHeader> daily(LocalDate d) {
		return jdbc.sql("select * from report_daily where business_date = :d").param("d", d)
				.query((rs, n) -> new DailyHeader(rs.getObject("business_date", LocalDate.class),
						rs.getObject("created_at", OffsetDateTime.class), rs.getString("created_by"),
						rs.getLong("journal_debit"), rs.getLong("journal_credit"), rs.getInt("unmapped_count")))
				.optional();
	}

	List<DailyReportBuilder.Line> dailyLines(LocalDate d) {
		return jdbc.sql("select section, item, item_count, amount from report_daily_line where business_date = :d"
				+ " order by id").param("d", d)
				.query((rs, n) -> new DailyReportBuilder.Line(rs.getString(1), rs.getString(2), rs.getLong(3),
						rs.getLong(4)))
				.list();
	}

	List<JournalMapper.AccountTotal> journalTotals(LocalDate d) {
		return jdbc.sql("select account_code, debit, credit from report_journal_line where business_date = :d"
				+ " order by account_code").param("d", d)
				.query((rs, n) -> new JournalMapper.AccountTotal(rs.getString(1), rs.getLong(2), rs.getLong(3)))
				.list();
	}

	// ------------------------------------------------------------------ 월마감

	void replaceMonthEnd(YearMonth month, LocalDate asOf, OffsetDateTime at, String by, List<MonthRow> rows) {
		LocalDate m = month.atDay(1);
		jdbc.sql("delete from report_month_end_account where month = :m").param("m", m).update();
		jdbc.sql("delete from report_month_end where month = :m").param("m", m).update();
		jdbc.sql("insert into report_month_end (month, as_of, created_at, created_by) values (:m, :asOf, :at, :by)")
				.param("m", m).param("asOf", asOf).param("at", at).param("by", by).update();
		for (MonthRow r : rows) {
			jdbc.sql("""
					insert into report_month_end_account (month, account_id, account_no, product_code, status, bucket,
					  delinquency_days, principal_balance, claim_balance)
					values (:m, :id, :no, :product, :status, :bucket, :days, :principal, :claim)
					""").param("m", m).param("id", r.accountId()).param("no", r.accountNo())
					.param("product", r.productCode()).param("status", r.status()).param("bucket", r.bucket().name())
					.param("days", r.delinquencyDays()).param("principal", r.principalBalance())
					.param("claim", r.claimBalance()).update();
		}
	}

	List<MonthHeader> months() {
		return jdbc.sql("select month, as_of, created_at from report_month_end order by month desc")
				.query((rs, n) -> new MonthHeader(rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class),
						rs.getObject(3, OffsetDateTime.class)))
				.list();
	}

	Optional<MonthHeader> month(YearMonth month) {
		return jdbc.sql("select month, as_of, created_at from report_month_end where month = :m")
				.param("m", month.atDay(1))
				.query((rs, n) -> new MonthHeader(rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class),
						rs.getObject(3, OffsetDateTime.class)))
				.optional();
	}

	List<MonthRow> monthRows(YearMonth month) {
		return jdbc.sql("select * from report_month_end_account where month = :m order by account_id")
				.param("m", month.atDay(1))
				.query((rs, n) -> new MonthRow(rs.getLong("account_id"), rs.getString("account_no"),
						rs.getString("product_code"), rs.getString("status"),
						java.util.Objects.requireNonNullElse(Bucket.parse(rs.getString("bucket")), Bucket.CURRENT),
						rs.getInt("delinquency_days"), rs.getLong("principal_balance"), rs.getLong("claim_balance")))
				.list();
	}
}
