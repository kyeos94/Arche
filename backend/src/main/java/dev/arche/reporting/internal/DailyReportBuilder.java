package dev.arche.reporting.internal;

import dev.arche.servicing.ServicingReports.AccountBalance;
import dev.arche.servicing.ServicingReports.LedgerLine;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 일보 항목 계산 (R65). 순수 함수: 처리일 기준 원장 거래 + 마감 직후 잔액 + 입금 집계 → (구역, 항목, 건수, 금액) 행.
 * 역분개는 원 거래유형으로 묶어 건수 −1, 금액은 부호 그대로 더한다 (당일 취소는 상계되어 0, R27).
 */
final class DailyReportBuilder {

	static final String DISBURSEMENT = "DISBURSEMENT";
	static final String POSTING = "POSTING";
	static final String ALLOCATION = "ALLOCATION";
	static final String ACCRUAL = "ACCRUAL";
	static final String CASH = "CASH";
	static final String BALANCE = "BALANCE";
	static final String STATUS = "STATUS";
	static final String BUCKET = "BUCKET";

	/** 입금 충당 계열 거래 (현금 입금·가수금·중도상환·반환 대기) */
	static final Set<String> PAYMENT_TYPES = Set.of("REPAYMENT", "SUSPENSE_IN", "SUSPENSE_APPLY", "PREPAYMENT",
			"REFUND");
	/** R28 정정 대상: 현금 유입 거래 (LoanAccounts.correctionsPostedOn과 같은 기준) */
	static final Set<String> CASH_IN_TYPES = Set.of("SUSPENSE_IN", "REPAYMENT");

	record Line(String section, String item, long count, long amount) {
	}

	record PaymentTotals(long allocatedCount, long allocatedTotal, long unmatchedOpenTotal) {
	}

	private DailyReportBuilder() {
	}

	/** 원장 거래만으로 만드는 행 (마감 스냅샷이 없는 과거 날짜도 계산 가능) */
	static List<Line> ledgerLines(LocalDate date, List<LedgerLine> entries) {
		Sums disb = new Sums();
		Sums posting = new Sums();
		Sums alloc = new Sums();
		Sums accrual = new Sums();
		long corrCount = 0;
		long corrAmount = 0;
		for (LedgerLine e : entries) {
			String type = e.effectiveType();
			int n = e.reversal() ? -1 : 1;
			if (type.equals("DISBURSE") || type.equals("ACQUIRE")) {
				disb.add(type, n, e.amount());
			}
			posting.add(type, n, e.amount());
			if (PAYMENT_TYPES.contains(type)) {
				alloc.add("PRINCIPAL", 0, e.principal());
				alloc.add("LATE_INTEREST", 0, e.lateInterest());
				alloc.add("NORMAL_INTEREST", 0, e.normalInterest());
				alloc.add("COST", 0, e.cost());
				alloc.add("FEE", 0, e.fee());
				alloc.add("PRE_ACQ_INTEREST", 0, e.preAcqInterest());
				alloc.add("SUSPENSE", 0, e.suspense());
				alloc.add("REFUND", 0, e.refund());
			}
			accrual.add("NORMAL_INTEREST", 0, e.normalAccrued());
			accrual.add("LATE_INTEREST", 0, e.lateAccrued());
			if (e.correctionId() != null && e.valueDate().isBefore(date) && CASH_IN_TYPES.contains(type)) {
				corrCount += n;
				corrAmount += e.amount();
			}
		}
		List<Line> lines = new ArrayList<>();
		disb.ensure("DISBURSE", "ACQUIRE");
		disb.to(DISBURSEMENT, lines);
		posting.to(POSTING, lines);
		alloc.ensure("PRINCIPAL", "LATE_INTEREST", "NORMAL_INTEREST", "COST", "FEE", "PRE_ACQ_INTEREST", "SUSPENSE",
				"REFUND");
		alloc.to(ALLOCATION, lines);
		accrual.ensure("NORMAL_INTEREST", "LATE_INTEREST");
		accrual.to(ACCRUAL, lines);
		lines.add(new Line(CASH, "CORRECTION", corrCount, corrAmount));
		return lines;
	}

	/** 마감 직후 잔액·상태·구간 행 */
	static List<Line> balanceLines(List<AccountBalance> balances, PaymentTotals payments) {
		List<Line> lines = new ArrayList<>();
		lines.add(new Line(CASH, "PAYMENT_ALLOCATED", payments.allocatedCount(), payments.allocatedTotal()));
		lines.add(new Line(CASH, "UNMATCHED_OPEN", 0, payments.unmatchedOpenTotal()));
		long open = 0;
		long[] b = new long[10];
		Map<String, long[]> status = new TreeMap<>();
		Map<Bucket, long[]> bucket = new LinkedHashMap<>();
		for (Bucket k : Bucket.values()) {
			bucket.put(k, new long[2]);
		}
		for (AccountBalance a : balances) {
			Bucket k = Bucket.of(a.status(), a.delinquencyDays());
			if (k.open()) {
				open++;
			}
			b[0] += a.principalBalance();
			b[1] += a.unpaidNormalInterest();
			b[2] += a.unpaidLateInterest();
			b[3] += a.costBalance();
			b[4] += a.preAcqInterest();
			b[5] += a.suspenseBalance();
			b[6] += a.refundPending();
			b[7] += a.accruedNormalInterest();
			b[8] += a.accruedLateInterest();
			b[9] += Provision.claim(a);
			long[] s = status.computeIfAbsent(a.status(), x -> new long[2]);
			s[0]++;
			s[1] += a.principalBalance();
			long[] c = bucket.get(k);
			c[0]++;
			c[1] += Provision.claim(a);
		}
		String[] items = { "PRINCIPAL", "UNPAID_NORMAL_INTEREST", "UNPAID_LATE_INTEREST", "COST", "PRE_ACQ_INTEREST",
				"SUSPENSE", "REFUND_PENDING", "ACCRUED_NORMAL_INTEREST", "ACCRUED_LATE_INTEREST", "CLAIM" };
		for (int i = 0; i < items.length; i++) {
			lines.add(new Line(BALANCE, items[i], i == 0 || i == 9 ? open : 0, b[i]));
		}
		status.forEach((k, v) -> lines.add(new Line(STATUS, k, v[0], v[1])));
		bucket.forEach((k, v) -> lines.add(new Line(BUCKET, k.name(), v[0], v[1])));
		return lines;
	}

	private static final class Sums {

		private final Map<String, long[]> map = new TreeMap<>();

		void add(String item, long count, long amount) {
			long[] v = map.computeIfAbsent(item, k -> new long[2]);
			v[0] += count;
			v[1] += amount;
		}

		void ensure(String... items) {
			for (String i : items) {
				map.computeIfAbsent(i, k -> new long[2]);
			}
		}

		void to(String section, List<Line> lines) {
			map.forEach((k, v) -> lines.add(new Line(section, k, v[0], v[1])));
		}
	}
}
