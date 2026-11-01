package dev.arche.servicing.internal;

import dev.arche.customer.Customers;
import dev.arche.servicing.ServicingReports;
import dev.arche.staff.DataScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 보고서용 읽기 전용 조회 (ADR 0009). servicing 자기 테이블만 읽는다.
 */
@Service
class JdbcServicingReports implements ServicingReports {

	static final int PAGE = 500;

	private final JdbcClient jdbc;
	private final Customers customers;

	private final LoanQuery loanQuery;

	JdbcServicingReports(JdbcClient jdbc, Customers customers, LoanQuery loanQuery) {
		this.loanQuery = loanQuery;
		this.jdbc = jdbc;
		this.customers = customers;
	}

	@Override
	public List<LedgerLine> ledgerPostedOn(LocalDate businessDate) {
		return jdbc.sql("""
				select e.*, a.account_no, o.type as reversed_type,
				       q.acquired_principal, q.pre_acq_interest as acq_pre_acq, q.acquired_cost
				from ledger_entry e
				join loan_account a on a.id = e.account_id
				left join ledger_entry o on o.id = e.reversal_of_id
				left join acquisition q on q.account_id = e.account_id
				     and (e.type = 'ACQUIRE' or o.type = 'ACQUIRE')
				where e.business_date = :d
				order by e.id
				""").param("d", businessDate).query((rs, n) -> ledgerLine(rs)).list();
	}

	private static LedgerLine ledgerLine(ResultSet rs) throws SQLException {
		long amount = rs.getLong("amount");
		Long acqPrincipal = (Long) rs.getObject("acquired_principal", Long.class);
		Acquired acquired = null;
		if (acqPrincipal != null) {
			long sign = amount < 0 ? -1 : 1;
			acquired = new Acquired(sign * acqPrincipal, sign * rs.getLong("acq_pre_acq"),
					sign * rs.getLong("acquired_cost"));
		}
		return new LedgerLine(rs.getLong("id"), rs.getLong("account_id"), rs.getString("account_no"),
				rs.getString("type"), rs.getString("reversed_type"), rs.getObject("value_date", LocalDate.class),
				rs.getObject("business_date", LocalDate.class), rs.getObject("payment_id", Long.class),
				rs.getObject("correction_id", Long.class), amount, rs.getLong("principal"),
				rs.getLong("normal_interest"), rs.getLong("late_interest"), rs.getLong("cost"), rs.getLong("fee"),
				rs.getLong("pre_acq_interest"), rs.getLong("suspense"), rs.getLong("refund"),
				rs.getLong("normal_accrued"), rs.getLong("late_accrued"), acquired);
	}

	@Override
	public List<AccountBalance> balances() {
		return jdbc.sql("""
				select id, account_no, product_code, origin, status, delinquency_days, principal_balance,
				       unpaid_normal_interest, unpaid_late_interest, cost_balance, pre_acq_interest,
				       suspense_balance, refund_pending, accrued_normal_interest, accrued_late_interest
				from loan_account order by id
				""").query((rs, n) -> new AccountBalance(rs.getLong("id"), rs.getString("account_no"),
				rs.getString("product_code"), rs.getString("origin"), rs.getString("status"),
				rs.getInt("delinquency_days"), rs.getLong("principal_balance"), rs.getLong("unpaid_normal_interest"),
				rs.getLong("unpaid_late_interest"), rs.getLong("cost_balance"), rs.getLong("pre_acq_interest"),
				rs.getLong("suspense_balance"), rs.getLong("refund_pending"), rs.getLong("accrued_normal_interest"),
				rs.getLong("accrued_late_interest"))).list();
	}

	/** LoanController.list와 같은 조건: 주민등록번호 형식이면 고객 일치, 아니면 계약번호 부분 일치 */
	@Override
	public void forEachLoan(dev.arche.servicing.LoanFilter filter, DataScope scope, Consumer<LoanListRow> consumer) {
		loanQuery.forEach(filter, scope, PAGE, r -> consumer.accept(new LoanListRow(r.id(), r.accountNo(), r.customerId(),
				r.productCode(), r.status(), r.principalBalance(), r.payoffAmount(), r.delinquencyDays(), r.nextDueDate(),
				r.assignedStaffId())));
	}
}
