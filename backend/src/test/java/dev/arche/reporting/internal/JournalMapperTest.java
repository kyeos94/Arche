package dev.arche.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arche.reporting.internal.JournalMapper.Component;
import dev.arche.reporting.internal.JournalMapper.Mode;
import dev.arche.reporting.internal.JournalMapper.Rule;
import dev.arche.servicing.ServicingReports.Acquired;
import dev.arche.servicing.ServicingReports.LedgerLine;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 원장 거래 → 분개 (R68). 규칙은 V16 시드와 같다.
 */
class JournalMapperTest {

	static final LocalDate D = LocalDate.of(2026, 7, 10);

	static JournalMapper.Rules seedRules() {
		Map<String, Rule> rules = new HashMap<>();
		put(rules, "DISBURSE", Mode.BOOK, "A-CASH", "A-LOAN");
		put(rules, "ACQUIRE", Mode.BOOK, "L-ACQPAY", "A-LOAN");
		put(rules, "COST_CHARGE", Mode.BOOK, "A-CASH", "A-COST");
		put(rules, "REPURCHASE", Mode.BOOK, "A-CASH", "A-LOAN");
		for (String t : List.of("REPAYMENT", "SUSPENSE_IN", "SUSPENSE_APPLY", "PREPAYMENT", "PREPAYMENT_FEE",
				"REFUND")) {
			put(rules, t, Mode.SETTLE, "A-CASH", null);
		}
		put(rules, "WAIVER", Mode.SETTLE, "E-WAIVER", null);
		put(rules, "ADJUSTMENT", Mode.SETTLE, "E-ADJ", null);
		put(rules, "CHARGE_OFF", Mode.SETTLE, "E-CHARGEOFF", null);
		put(rules, "SALE", Mode.SETTLE, "E-SALE", null);
		Map<Component, String> c = new EnumMap<>(Component.class);
		c.put(Component.PRINCIPAL, "A-LOAN");
		c.put(Component.NORMAL_INTEREST, "A-INT");
		c.put(Component.LATE_INTEREST, "A-LATE");
		c.put(Component.PRE_ACQ_INTEREST, "A-PREACQ");
		c.put(Component.COST, "A-COST");
		c.put(Component.FEE, "I-FEE");
		c.put(Component.SUSPENSE, "L-SUSP");
		c.put(Component.REFUND, "L-REFUND");
		c.put(Component.NORMAL_INCOME, "I-INT");
		c.put(Component.LATE_INCOME, "I-LATE");
		return new JournalMapper.Rules(rules, c);
	}

	private static void put(Map<String, Rule> rules, String type, Mode mode, String counter, String book) {
		rules.put(type, new Rule(type, mode, counter, book));
	}

	static long seq = 1;

	/** 금액 필드: amount, principal, normal, late, cost, fee, preAcq, suspense, refund, normalAccrued, lateAccrued */
	static LedgerLine entry(String type, String reversedType, long... f) {
		return new LedgerLine(seq++, 1, "L0001", type, reversedType, D, D, null, null, f[0], f[1], f[2], f[3], f[4],
				f[5], f[6], f[7], f[8], f[9], f[10], null);
	}

	private final JournalMapper mapper = new JournalMapper(seedRules());

	/** "계정:차변/대변" 집합으로 비교 (같은 계정이 여러 행이면 합산) */
	private Map<String, String> signature(LedgerLine e) {
		return mapper.map(e).stream().collect(Collectors.groupingBy(JournalMapper.Line::accountCode,
				java.util.TreeMap::new, Collectors.collectingAndThen(Collectors.toList(),
						ls -> ls.stream().mapToLong(JournalMapper.Line::debit).sum() + "/"
								+ ls.stream().mapToLong(JournalMapper.Line::credit).sum())));
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', textBlock = """
			# 설명                    | 유형            | 금액 필드 (amount principal normal late cost fee preAcq suspense refund nAcc lAcc) | 기대 (계정=차변/대변 ; ...)
			실행                      | DISBURSE       | 10000000 0 0 0 0 0 0 0 0 0 0                    | A-CASH=0/10000000;A-LOAN=10000000/0
			비용 적재                 | COST_CHARGE    | 50000 0 0 0 0 0 0 0 0 0 0                       | A-CASH=0/50000;A-COST=50000/0
			연체 중 입금 (G11 형태)   | REPAYMENT      | 1135616 1000000 120000 15616 0 0 0 0 0 120000 15616 | A-CASH=1135616/0;A-INT=120000/120000;A-LATE=15616/15616;A-LOAN=0/1000000;I-INT=0/120000;I-LATE=0/15616
			약정일 전 입금 = 가수금   | SUSPENSE_IN    | 500000 0 0 0 0 0 0 500000 0 0 0                 | A-CASH=500000/0;L-SUSP=0/500000
			가수금 충당 (현금 없음)   | SUSPENSE_APPLY | 300000 250000 50000 0 0 0 0 -300000 0 50000 0   | A-INT=50000/50000;A-LOAN=0/250000;I-INT=0/50000;L-SUSP=300000/0
			중도상환 (가수금 사용)    | PREPAYMENT     | 1010000 1000000 0 0 0 10000 0 -1010000 0 0 0    | A-LOAN=0/1000000;I-FEE=0/10000;L-SUSP=1010000/0
			완제 후 입금 = 반환 대기  | REFUND         | 7000 0 0 0 0 0 0 0 7000 0 0                     | A-CASH=7000/0;L-REFUND=0/7000
			완제 시 가수금 → 반환     | REFUND         | 3000 0 0 0 0 0 0 -3000 3000 0 0                 | L-REFUND=0/3000;L-SUSP=3000/0
			채무조정 감면             | WAIVER         | 40000 0 30000 10000 0 0 0 0 0 0 0               | A-INT=0/30000;A-LATE=0/10000;E-WAIVER=40000/0
			감면 부활 (음수)          | ADJUSTMENT     | -40000 0 -30000 -10000 0 0 0 0 0 0 0            | A-INT=30000/0;A-LATE=10000/0;E-ADJ=0/40000
			미매핑 유형 → 미결산      | SETOFF         | 100000 100000 0 0 0 0 0 0 0 0 0                 | A-LOAN=0/100000;X-SUSPENSE=100000/0
			""")
	void 거래유형별_분개(String desc, String type, String fields, String expected) {
		long[] f = java.util.Arrays.stream(fields.trim().split("\\s+")).mapToLong(Long::parseLong).toArray();
		LedgerLine e = entry(type, null, f);
		Map<String, String> expect = new java.util.TreeMap<>();
		for (String part : expected.split(";")) {
			String[] kv = part.trim().split("=");
			expect.put(kv[0], kv[1]);
		}
		assertThat(signature(e)).as(desc).isEqualTo(expect);
		List<JournalMapper.Line> lines = mapper.map(e);
		assertThat(lines.stream().mapToLong(JournalMapper.Line::debit).sum()).as("차변 = 대변")
				.isEqualTo(lines.stream().mapToLong(JournalMapper.Line::credit).sum());
		assertThat(lines).allSatisfy(l -> assertThat(l.debit() >= 0 && l.credit() >= 0
				&& (l.debit() == 0 || l.credit() == 0)).isTrue());
	}

	@Test
	void 역분개는_원_거래의_좌우를_바꾼다() {
		LedgerLine original = entry("REPAYMENT", null, 1135616, 1000000, 120000, 15616, 0, 0, 0, 0, 0, 120000, 15616);
		LedgerLine reversal = entry("REVERSAL", "REPAYMENT", -1135616, -1000000, -120000, -15616, 0, 0, 0, 0, 0,
				-120000, -15616);
		List<JournalMapper.Line> a = mapper.map(original);
		List<JournalMapper.Line> b = mapper.map(reversal);
		assertThat(b).hasSameSizeAs(a);
		for (int i = 0; i < a.size(); i++) {
			assertThat(b.get(i).accountCode()).isEqualTo(a.get(i).accountCode());
			assertThat(b.get(i).debit()).isEqualTo(a.get(i).credit());
			assertThat(b.get(i).credit()).isEqualTo(a.get(i).debit());
			assertThat(b.get(i).postingType()).isEqualTo("REPAYMENT");
			assertThat(b.get(i).reversal()).isTrue();
		}
		JournalMapper.Day day = mapper.day(List.of(original, reversal));
		assertThat(day.balanced()).isTrue();
		assertThat(day.totals()).allSatisfy(t -> assertThat(t.debit()).isEqualTo(t.credit()));
	}

	@Test
	void 양수는_원금_양수전이자_비용으로_나눠_계상한다() {
		LedgerLine e = new LedgerLine(seq++, 1, "L0002", "ACQUIRE", null, D, D, null, null, 5_300_000, 0, 0, 0, 0, 0,
				0, 0, 0, 0, 0, new Acquired(5_000_000, 200_000, 100_000));
		assertThat(signature(e)).containsExactlyInAnyOrderEntriesOf(Map.of("A-LOAN", "5000000/0", "A-PREACQ",
				"200000/0", "A-COST", "100000/0", "L-ACQPAY", "0/5300000"));
	}

	@Test
	void 일자_합계와_미매핑_유형을_보고한다() {
		List<LedgerLine> entries = List.of(
				entry("DISBURSE", null, 10_000_000, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
				entry("SETOFF", null, 100_000, 100_000, 0, 0, 0, 0, 0, 0, 0, 0, 0),
				entry("REVERSAL", "SETOFF", -100_000, -100_000, 0, 0, 0, 0, 0, 0, 0, 0, 0),
				entry("SETOFF", null, 50_000, 50_000, 0, 0, 0, 0, 0, 0, 0, 0, 0));
		JournalMapper.Day day = mapper.day(entries);
		assertThat(day.balanced()).isTrue();
		assertThat(day.debit()).isEqualTo(10_000_000 + 100_000 + 100_000 + 50_000);
		assertThat(day.unmapped()).containsExactly(new JournalMapper.Unmapped("SETOFF", 1, 50_000));
		assertThat(day.lines()).filteredOn(JournalMapper.Line::unmapped).hasSize(6);
	}
}
