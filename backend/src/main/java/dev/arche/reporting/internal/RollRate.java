package dev.arche.reporting.internal;

import dev.arche.common.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 연체 전이율 (R66). 전월 말 구간 i → 당월 말 구간 j. 순수 함수.
 * 비율은 전월 말 구간 i 잔액(신규 행은 당월 말 잔액) 대비, 소수 첫째 자리까지 (Money.percentOf, R25 방식).
 */
final class RollRate {

	private RollRate() {
	}

	/** 월말 계약 1건 */
	record Position(long accountId, Bucket bucket, long claim) {
	}

	record Cell(Bucket to, long count, long balance, BigDecimal countRate, BigDecimal balanceRate) {
	}

	/**
	 * @param from null이면 "신규" 행 (전월 말에 없던 계약)
	 */
	record Row(Bucket from, long count, long balance, List<Cell> cells, BigDecimal worsenRate, BigDecimal cureRate) {
	}

	record Result(List<Bucket> columns, List<Row> rows) {
	}

	/** 행: 열린 구간 + 신규, 열: 열린 구간 + 종결 */
	static Result of(List<Position> previous, List<Position> current) {
		Map<Long, Position> now = new java.util.HashMap<>();
		current.forEach(p -> now.put(p.accountId(), p));
		List<Bucket> open = Arrays.stream(Bucket.values()).filter(Bucket::open).toList();
		List<Bucket> columns = new ArrayList<>(open);
		columns.add(Bucket.CLOSED);

		Map<Bucket, long[][]> matrix = new EnumMap<>(Bucket.class);
		long[][] fresh = new long[columns.size()][2];
		java.util.Set<Long> seen = new java.util.HashSet<>();
		for (Position p : previous) {
			if (!p.bucket().open()) {
				continue;
			}
			seen.add(p.accountId());
			Position n = now.get(p.accountId());
			Bucket to = n == null ? Bucket.CLOSED : n.bucket();
			long[][] m = matrix.computeIfAbsent(p.bucket(), k -> new long[columns.size()][2]);
			m[columns.indexOf(to)][0]++;
			m[columns.indexOf(to)][1] += p.claim();
		}
		for (Position n : current) {
			if (!seen.contains(n.accountId()) && n.bucket().open()) {
				fresh[columns.indexOf(n.bucket())][0]++;
				fresh[columns.indexOf(n.bucket())][1] += n.claim();
			}
		}
		List<Row> rows = new ArrayList<>();
		for (Bucket b : open) {
			rows.add(row(b, matrix.getOrDefault(b, new long[columns.size()][2]), columns));
		}
		rows.add(row(null, fresh, columns));
		return new Result(columns, rows);
	}

	private static Row row(Bucket from, long[][] m, List<Bucket> columns) {
		long count = 0;
		long balance = 0;
		for (long[] c : m) {
			count += c[0];
			balance += c[1];
		}
		List<Cell> cells = new ArrayList<>();
		long worse = 0;
		long better = 0;
		for (int i = 0; i < columns.size(); i++) {
			Bucket to = columns.get(i);
			cells.add(new Cell(to, m[i][0], m[i][1], pct(m[i][0], count), pct(m[i][1], balance)));
			if (from != null && to.open() && to.ordinal() > from.ordinal()) {
				worse += m[i][1];
			}
			if (from != null && to.open() && to.ordinal() < from.ordinal()) {
				better += m[i][1];
			}
		}
		return new Row(from, count, balance, cells, pct(worse, balance), pct(better, balance));
	}

	private static BigDecimal pct(long part, long whole) {
		return Money.won(part).percentOf(Money.won(whole));
	}
}
