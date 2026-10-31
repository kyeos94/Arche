package dev.arche.collection;

import static java.time.LocalDate.parse;
import static org.assertj.core.api.Assertions.assertThat;

import dev.arche.collection.ContactQuotaGuard.Attempt;
import dev.arche.collection.ContactQuotaGuard.Channel;
import dev.arche.common.Money;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CollectionGoldenTest {

	@Test
	void G14_추심_연락_7일_7회() {
		var guard = new ContactQuotaGuard();
		record Row(String date, Channel channel, int count, boolean allowed) {
		}
		List<Row> rows = List.of(
				new Row("2026-06-01", Channel.CALL, 0, true),
				new Row("2026-06-02", Channel.SMS, 1, true),
				new Row("2026-06-02", Channel.CALL, 2, true),
				new Row("2026-06-04", Channel.VISIT, 3, true),
				new Row("2026-06-05", Channel.SMS, 4, true),
				new Row("2026-06-06", Channel.CALL, 5, true),
				new Row("2026-06-07", Channel.SMS, 6, true),
				new Row("2026-06-07", Channel.CALL, 7, false),
				new Row("2026-06-08", Channel.CALL, 6, true),
				new Row("2026-06-08", Channel.STATUTORY_NOTICE, -1, true),
				new Row("2026-06-08", Channel.SMS, 7, false),
				new Row("2026-06-09", Channel.SMS, 5, true));
		for (int i = 0; i < rows.size(); i++) {
			Row row = rows.get(i);
			var d = guard.attempt(new Attempt(parse(row.date()), row.channel()));
			assertThat(d.allowed()).as("#" + (i + 1)).isEqualTo(row.allowed());
			if (row.count() >= 0) {
				assertThat(d.countInWindow()).as("#" + (i + 1)).isEqualTo(row.count());
			}
		}
	}

	@Test
	void G19_분할납부_약속_이행_판정() {
		var deposits = List.of(
				new PromiseTracker.Deposit(parse("2026-08-07"), Money.won(300_000)),
				new PromiseTracker.Deposit(parse("2026-09-08"), Money.won(280_000)),
				new PromiseTracker.Deposit(parse("2026-10-07"), Money.won(250_000)),
				new PromiseTracker.Deposit(parse("2026-12-09"), Money.won(100_000)));
		var result = PromiseTracker.judge(parse("2026-08-01"), YearMonth.of(2026, 8), 7, Money.won(300_000), 5,
				deposits);

		assertThat(result).extracting(PromiseTracker.Judgment::fulfilled).containsExactly(true, true, false, false,
				false);
		assertThat(result).extracting(PromiseTracker.Judgment::consecutiveMisses).containsExactly(0, 0, 1, 2, 3);
		assertThat(result).extracting(PromiseTracker.Judgment::action).containsExactly(PromiseTracker.Action.NONE,
				PromiseTracker.Action.NONE, PromiseTracker.Action.NOTIFY_STAFF, PromiseTracker.Action.NOTIFY_STAFF,
				PromiseTracker.Action.TERMINATE);
	}

	@ParameterizedTest
	@CsvSource({ "2026-01-31, 2026-02-28", "2026-03-31, 2026-03-31" })
	void R18_약속일이_없는_달은_말일(String registered, String expectedPromise) {
		var r = PromiseTracker.judge(parse("2026-01-01"), YearMonth.from(parse(expectedPromise)), 31,
				Money.won(100_000), 1, List.of());
		assertThat(r.getFirst().promiseDate()).isEqualTo(parse(expectedPromise));
	}
}
