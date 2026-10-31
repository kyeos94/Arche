package dev.arche.servicing.engine;

import static dev.arche.servicing.engine.Golden.G1;
import static dev.arche.servicing.engine.Golden.concat;
import static dev.arche.servicing.engine.Golden.pay;
import static dev.arche.servicing.engine.Golden.payScheduled;
import static dev.arche.servicing.engine.Golden.simulate;
import static java.time.LocalDate.parse;
import static org.assertj.core.api.Assertions.assertThat;

import dev.arche.common.InterestRate;
import dev.arche.common.Money;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

/**
 * 스케줄 기대값 (G1~G4, G20, G24, G25) — golden/schedules.csv 한 행이 회차 하나.
 */
class GoldenScheduleTest {

	private static final Map<String, Supplier<SimulationResult>> CASES = Map.of(
			"G1", () -> simulate(G1, List.of(), "2026-01-10"),
			"G2", () -> simulate(Golden.G2, List.of(), "2026-03-20"),
			"G3", () -> simulate(Golden.G3, List.of(), "2026-05-15"),
			"G4", () -> simulate(Golden.G4, List.of(), "2026-01-05"),
			// 6회차까지 정상 납부 후 08-01에 원금 2,000,000원 일부 중도상환 (R7)
			"G20", () -> simulate(G1, concat(payScheduled(G1, 6), List.of(pay("PRE", "2026-08-01", 2_071_713),
					new LoanEvent.PrepaymentRequest("PRE-REQ", parse("2026-08-01"), Money.won(2_000_000)))),
					"2026-08-01"),
			// 06-25부터 19.9% → 17.9% (R20), 6회차는 구간 합산 이자로 납부
			"G24", () -> simulate(G1.withRatePeriods(List.of(
					new RatePeriod(parse("2026-01-10"), InterestRate.of("19.9")),
					new RatePeriod(parse("2026-06-25"), InterestRate.of("17.9")))),
					concat(payScheduled(G1, 5), List.of(pay("INST-6", "2026-07-10", 920_880))), "2026-07-10"),
			// 07-15에 약정일 10일 → 25일 변경 신청 (R21), 7회차까지 납부
			"G25", () -> simulate(G1, concat(payScheduled(G1, 7),
					List.of(new LoanEvent.DueDayChange("DUE", parse("2026-07-15"), 25))), "2026-08-10"),
			// G8 상태에서 09-01 채무조정: 가산이자 전액 감면, 정상이자 체결일 납부, 24회 원리금균등 (R9)
			"G23", () -> simulate(Golden.G2, GoldenScenarioTest.g23Events(), "2026-09-01"));

	private static final Map<String, SimulationResult> CACHE = new ConcurrentHashMap<>();

	@ParameterizedTest(name = "{0} {1}회차 {2}")
	@CsvFileSource(resources = "/golden/schedules.csv", numLinesToSkip = 1)
	void 회차별_원금_이자_잔액이_기대값과_같다(String caseId, int seq, LocalDate dueDate, long days, long principal,
			long interest, long payment, long balance) {
		SimulationResult result = CACHE.computeIfAbsent(caseId, id -> CASES.get(id).get());
		Installment inst = result.installment(seq).orElseThrow();

		assertThat(inst.dueDate()).isEqualTo(dueDate);
		assertThat(ChronoUnit.DAYS.between(inst.periodStart(), inst.dueDate())).isEqualTo(days);
		assertThat(inst.principalDue()).isEqualTo(Money.won(principal));
		assertThat(inst.plannedInterest()).isEqualTo(Money.won(interest));
		assertThat(inst.plannedPayment()).isEqualTo(Money.won(payment));
		long after = result.installments().stream().filter(i -> i.seq() > seq)
				.mapToLong(i -> i.principalDue().won()).sum();
		assertThat(after).isEqualTo(balance);
	}
}
