package dev.arche.reporting.internal;

import dev.arche.servicing.ServicingReports.LedgerLine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 원장 거래 → 복식부기 분개 (R68, guide 6.1). 순수 함수: DB·Spring·시계에 의존하지 않는다.
 * <p>
 * 거래마다 "항목은 정해진 계정, 나머지(잔차)는 상대 계정"으로 만들어 차변 = 대변이 구조적으로 성립한다.
 * 금액은 부호 있는 값으로 계산하고(+ 차변, − 대변) 마지막에 차변·대변 열로 옮기므로, 역분개(음수 금액)는 원 거래 분개의 좌우가 바뀐다.
 */
final class JournalMapper {

	/** 매핑이 없는 거래유형의 상대 계정 */
	static final String UNMAPPED_ACCOUNT = "X-SUSPENSE";

	enum Mode {
		/** 자산 계상: 금액을 계상 계정 차변, 상대 계정 대변 (실행·양수·비용 적재·환매) */
		BOOK,
		/** 충당 항목별 대변 + 잔차를 상대 계정 차변 (입금·가수금·감면·조정 등) */
		SETTLE
	}

	/** 충당 항목 (journal_component_account.component) */
	enum Component {
		PRINCIPAL, NORMAL_INTEREST, LATE_INTEREST, COST, FEE, PRE_ACQ_INTEREST, SUSPENSE, REFUND,
		/** 확정 이자의 수익 계정 (R1) */
		NORMAL_INCOME, LATE_INCOME
	}

	record Rule(String postingType, Mode mode, String counterAccount, String bookAccount) {
	}

	record Rules(Map<String, Rule> byType, Map<Component, String> componentAccounts) {

		String account(Component c) {
			return componentAccounts.getOrDefault(c, UNMAPPED_ACCOUNT);
		}
	}

	/**
	 * 분개 1행.
	 *
	 * @param postingType 원장 거래유형 (역분개는 원 거래유형)
	 * @param unmapped    매핑 규칙이 없어 미결산 계정을 쓴 거래
	 */
	record Line(long entryId, String accountNo, String postingType, boolean reversal, String accountCode,
			long debit, long credit, String memo, boolean unmapped) {
	}

	record AccountTotal(String accountCode, long debit, long credit) {
	}

	record Unmapped(String postingType, long count, long amount) {
	}

	record Day(List<Line> lines, List<AccountTotal> totals, long debit, long credit, List<Unmapped> unmapped) {

		boolean balanced() {
			return debit == credit;
		}
	}

	private final Rules rules;

	JournalMapper(Rules rules) {
		this.rules = rules;
	}

	List<Line> map(LedgerLine e) {
		String type = e.effectiveType();
		Rule rule = rules.byType().get(type);
		boolean unmapped = rule == null;
		Mode mode = unmapped ? Mode.SETTLE : rule.mode();
		String counter = unmapped ? UNMAPPED_ACCOUNT : rule.counterAccount();
		Builder b = new Builder(e, type, unmapped);
		if (mode == Mode.BOOK) {
			long rest = e.amount();
			if (e.acquired() != null) {
				b.add(rules.account(Component.PRINCIPAL), e.acquired().principal(), "양수 원금");
				b.add(rules.account(Component.PRE_ACQ_INTEREST), e.acquired().preAcqInterest(), "양수 전 미수이자");
				b.add(rules.account(Component.COST), e.acquired().cost(), "양수 비용");
				rest -= e.acquired().principal() + e.acquired().preAcqInterest() + e.acquired().cost();
			}
			b.add(rule.bookAccount() == null ? UNMAPPED_ACCOUNT : rule.bookAccount(), rest, "계상");
			b.add(counter, -e.amount(), "상대");
		} else {
			long residual = 0;
			residual += b.credit(Component.PRINCIPAL, e.principal(), "원금");
			residual += b.credit(Component.LATE_INTEREST, e.lateInterest(), "가산이자");
			residual += b.credit(Component.NORMAL_INTEREST, e.normalInterest(), "정상이자");
			residual += b.credit(Component.COST, e.cost(), "비용");
			residual += b.credit(Component.FEE, e.fee(), "수수료");
			residual += b.credit(Component.PRE_ACQ_INTEREST, e.preAcqInterest(), "양수 전 이자");
			residual += b.credit(Component.SUSPENSE, e.suspense(), "가수금");
			residual += b.credit(Component.REFUND, e.refund(), "반환 대기");
			b.add(counter, residual, unmapped ? "미매핑 거래유형" : "상대");
		}
		// 정산 이벤트에서 확정된 이자 (R1): 차변 미수이자 / 대변 이자수익
		b.add(rules.account(Component.NORMAL_INTEREST), e.normalAccrued(), "정상이자 확정");
		b.add(rules.account(Component.NORMAL_INCOME), -e.normalAccrued(), "정상이자 확정");
		b.add(rules.account(Component.LATE_INTEREST), e.lateAccrued(), "가산이자 확정");
		b.add(rules.account(Component.LATE_INCOME), -e.lateAccrued(), "가산이자 확정");
		return b.lines;
	}

	Day day(List<LedgerLine> entries) {
		List<Line> lines = new ArrayList<>();
		Map<String, long[]> byAccount = new LinkedHashMap<>();
		Map<String, long[]> unmapped = new LinkedHashMap<>();
		long debit = 0;
		long credit = 0;
		for (LedgerLine e : entries) {
			List<Line> mapped = map(e);
			lines.addAll(mapped);
			for (Line l : mapped) {
				long[] t = byAccount.computeIfAbsent(l.accountCode(), k -> new long[2]);
				t[0] += l.debit();
				t[1] += l.credit();
				debit += l.debit();
				credit += l.credit();
			}
			if (!rules.byType().containsKey(e.effectiveType())) {
				long[] u = unmapped.computeIfAbsent(e.effectiveType(), k -> new long[2]);
				u[0] += e.reversal() ? -1 : 1;
				u[1] += e.amount();
			}
		}
		Set<String> codes = new TreeSet<>(byAccount.keySet());
		List<AccountTotal> totals = codes.stream()
				.map(c -> new AccountTotal(c, byAccount.get(c)[0], byAccount.get(c)[1])).toList();
		List<Unmapped> u = unmapped.entrySet().stream()
				.map(x -> new Unmapped(x.getKey(), x.getValue()[0], x.getValue()[1])).toList();
		return new Day(lines, totals, debit, credit, u);
	}

	private final class Builder {

		private final LedgerLine e;
		private final String type;
		private final boolean unmapped;
		private final List<Line> lines = new ArrayList<>();

		Builder(LedgerLine e, String type, boolean unmapped) {
			this.e = e;
			this.type = type;
			this.unmapped = unmapped;
		}

		/** 항목 금액을 대변에 두고, 잔차에 더할 값을 돌려준다 */
		long credit(Component c, long amount, String memo) {
			add(rules.account(c), -amount, memo);
			return amount;
		}

		/** signed: + 차변, − 대변 */
		void add(String account, long signed, String memo) {
			if (signed == 0) {
				return;
			}
			lines.add(new Line(e.entryId(), e.accountNo(), type, e.reversal(), account, Math.max(signed, 0),
					Math.max(-signed, 0), e.reversal() ? memo + " (역분개)" : memo, unmapped));
		}
	}
}
