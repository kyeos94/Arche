package dev.arche.servicing.engine;

import static java.time.LocalDate.parse;

import dev.arche.common.BusinessCalendar;
import dev.arche.common.InterestRate;
import dev.arche.common.Money;
import dev.arche.common.RepaymentMethod;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * docs/golden-cases.md의 대출 조건과 정책서 8장 2026년 휴일.
 */
final class Golden {

	static final BusinessCalendar CALENDAR = new BusinessCalendar(Set.of(
			parse("2026-01-01"), parse("2026-02-16"), parse("2026-02-17"), parse("2026-02-18"),
			parse("2026-03-02"), parse("2026-05-05"), parse("2026-05-25"), parse("2026-06-03"),
			parse("2026-08-17"), parse("2026-09-24"), parse("2026-09-25"), parse("2026-09-28"),
			parse("2026-10-05"), parse("2026-10-09"), parse("2026-12-25")));

	static final LoanSimulator SIMULATOR = new LoanSimulator(CALENDAR);

	/** G1. P1 원리금균등 10,000,000원 · 19.9% · 12개월 · 2026-01-10 · 약정일 10일 */
	static final LoanTerms G1 = LoanTerms.originated(RepaymentMethod.EQUAL_INSTALLMENT, 10_000_000,
			parse("2026-01-10"), 12, 0, 10, "19.9", "1.0");

	/** G2. P3 원금균등 12,000,000원 · 15.0% · 12개월 · 2026-03-20 · 약정일 25일 */
	static final LoanTerms G2 = LoanTerms.originated(RepaymentMethod.EQUAL_PRINCIPAL, 12_000_000,
			parse("2026-03-20"), 12, 0, 25, "15.0", "1.2");

	/** G3. P2 만기일시 5,000,000원 · 19.0% · 6개월 · 2026-05-15 · 약정일 15일 */
	static final LoanTerms G3 = LoanTerms.originated(RepaymentMethod.BULLET, 5_000_000, parse("2026-05-15"), 6, 0,
			15, "19.0", "0");

	/** G4. P4 거치 6 + 분할 12, 30,000,000원 · 12.0% · 2026-01-05 · 약정일 5일 */
	static final LoanTerms G4 = LoanTerms.originated(RepaymentMethod.GRACE_THEN_INSTALLMENT, 30_000_000,
			parse("2026-01-05"), 18, 6, 5, "12.0", "1.0");

	/** G15. P5 자유상환 10,000,000원 · 19.5% · 2026-02-20 · 약정일 20일 (만기 12개월로 둠) */
	static final LoanTerms G15 = LoanTerms.originated(RepaymentMethod.REVOLVING, 10_000_000, parse("2026-02-20"),
			12, 0, 20, "19.5", "0");

	/** G17. N1 양수 2026-03-02 · 취득가 800,000 · 원금 4,000,000 / 미수이자 1,200,000 / 비용 150,000 · 19.0% */
	static LoanTerms g17(boolean interestCharged) {
		return LoanTerms.acquired(Money.won(4_000_000), new Acquisition(parse("2026-03-02"), Money.won(800_000),
				Money.won(1_200_000), Money.won(150_000),
				Acquisition.appliedRate(InterestRate.of("16.0"), InterestRate.of("19.0")), interestCharged));
	}

	private Golden() {
	}

	static LoanEvent.Payment pay(String ref, String bankDate, long amount) {
		return new LoanEvent.Payment(ref, parse(bankDate), Money.won(amount));
	}

	/** 1~k회차를 스케줄대로 약정일에 납부하는 입금 이벤트 */
	static List<LoanEvent> payScheduled(LoanTerms terms, int k) {
		SimulationResult plan = SIMULATOR.simulate(terms, List.of(), terms.disbursedOn());
		List<LoanEvent> events = new ArrayList<>();
		for (Installment i : plan.installments()) {
			if (i.seq() <= k) {
				events.add(new LoanEvent.Payment("INST-" + i.seq(), i.dueDate(), i.plannedPayment()));
			}
		}
		return events;
	}

	@SafeVarargs
	static List<LoanEvent> concat(List<? extends LoanEvent>... lists) {
		return Stream.of(lists).<LoanEvent>flatMap(List::stream).toList();
	}

	static SimulationResult simulate(LoanTerms terms, List<LoanEvent> events, String asOf) {
		return SIMULATOR.simulate(terms, events, LocalDate.parse(asOf));
	}

	static Posting posting(SimulationResult r, String ref) {
		return r.postings().stream().filter(p -> p.sourceRef().equals(ref)).reduce((a, b) -> b).orElseThrow();
	}
}
