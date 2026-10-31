package dev.arche.operation.internal;

import static java.time.LocalDate.parse;

import dev.arche.common.InterestRate;
import dev.arche.common.Money;
import dev.arche.customer.CustomerView;
import dev.arche.customer.Customers;
import dev.arche.payment.Payments;
import dev.arche.servicing.AccountRef;
import dev.arche.servicing.LoanAccounts;
import dev.arche.staff.CurrentStaff;
import dev.arche.staff.StaffDirectory;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 데모 데이터 (arche.demo.enabled=true). 업무일자 2026-06-12에 화면에서 golden 사례를 재현할 수 있게 만든다.
 * <ul>
 * <li>계약 A (G2, P3): 1회차만 납부 → 연체 18일. 미확인 입금 05-26 1,135,616원을 A에 매칭하면 G11, 그 입금을 취소하면 G27</li>
 * <li>계약 B (P5, 잔액 3,000,000): 미확인 입금을 A에서 B로 정정 이동하면 G28</li>
 * <li>계약 C (G1, P1): 5회차까지 정상 납부. 금리·약정일 변경(G24·G25), 일부 중도상환(G20) 시연</li>
 * <li>계약 D (G15, P5), 계약 E (G17, N1 매입채권)</li>
 * </ul>
 */
@Component
@Order(10)
@ConditionalOnProperty(name = "arche.demo.enabled", havingValue = "true")
class DemoDataLoader implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(DemoDataLoader.class);
	private static final String MARKER_RESIDENT_NO = "800101-1000001";

	private final Customers customers;
	private final LoanAccounts accounts;
	private final Payments payments;
	private final StaffDirectory staff;

	DemoDataLoader(Customers customers, LoanAccounts accounts, Payments payments, StaffDirectory staff) {
		this.customers = customers;
		this.accounts = accounts;
		this.payments = payments;
		this.staff = staff;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (customers.findByResidentNo(MARKER_RESIDENT_NO).isPresent()) {
			return;
		}
		Long col1 = staff.byLoginId("col1").map(CurrentStaff::id).orElse(null);
		Long col2 = staff.byLoginId("col2").map(CurrentStaff::id).orElse(null);

		AccountRef a = open(customer("홍길동", MARKER_RESIDENT_NO, "1980-01-01"), "P3", 12_000_000, "15.0", 12, 0, 25,
				"2026-03-20", col1);
		pay(a, "2026-04-25", 1_177_534, "DEMO-A-1", "홍길동");
		unmatched("2026-05-26", 1_135_616, "DEMO-U-1", "홍길동");

		AccountRef b = open(customer("김철수", "850505-1000002", "1985-05-05"), "P5", 3_000_000, "19.5", 12, 0, 20,
				"2026-04-20", col1);
		pay(b, "2026-05-20", 48_082, "DEMO-B-1", "김철수");

		AccountRef c = open(customer("이영희", "900303-2000003", "1990-03-03"), "P1", 10_000_000, "19.9", 12, 0, 10,
				"2026-01-10", col2);
		for (String due : new String[] { "2026-02-10", "2026-03-10", "2026-04-10", "2026-05-10", "2026-06-10" }) {
			pay(c, due, 925_866, "DEMO-C-" + due, "이영희");
		}

		AccountRef d = open(customer("박민수", "880808-1000004", "1988-08-08"), "P5", 10_000_000, "19.5", 12, 0, 20,
				"2026-02-20", col1);
		pay(d, "2026-03-20", 149_589, "DEMO-D-1", "박민수");
		pay(d, "2026-04-20", 2_000_000, "DEMO-D-2", "박민수");
		pay(d, "2026-05-11", 1_000_000, "DEMO-D-3", "박민수");
		pay(d, "2026-05-20", 34_894, "DEMO-D-4", "박민수");

		CustomerView npl = customer("최매입", "750707-1000005", "1975-07-07");
		AccountRef e = accounts.acquire(new LoanAccounts.AcquireCommand(npl.id(), parse("2026-03-02"),
				Money.won(800_000), "가상저축은행(가상)", parse("2021-05-10"), InterestRate.of("16.0"),
				InterestRate.of("19.0"), Money.won(4_000_000), Money.won(1_200_000), Money.won(150_000), true,
				parse("2023-02-11"), parse("2023-02-11"), null, col2));
		pay(e, "2026-03-31", 300_000, "DEMO-E-1", "최매입");
		pay(e, "2026-05-15", 1_000_000, "DEMO-E-2", "최매입");

		log.info("데모 데이터 생성: 계약 {} {} {} {} {}", a.accountNo(), b.accountNo(), c.accountNo(), d.accountNo(),
				e.accountNo());
	}

	private CustomerView customer(String name, String residentNo, String birth) {
		return customers.registerOrFind(new Customers.NewCustomer(name, residentNo, LocalDate.parse(birth),
				"010-1234-" + residentNo.substring(10), "서울시 가상구 예시로 1"));
	}

	private AccountRef open(CustomerView c, String product, long principal, String rate, int term, int grace,
			int dueDay, String disbursedOn, Long staffId) {
		return accounts.open(new LoanAccounts.OpenCommand(c.id(), product, Money.won(principal),
				InterestRate.of(rate), term, grace, dueDay, parse(disbursedOn), staffId, null));
	}

	private void pay(AccountRef account, String date, long amount, String txId, String depositor) {
		payments.register(new Payments.Deposit(account.id(), Payments.Channel.BANK_TRANSFER, parse(date),
				Money.won(amount), depositor, txId));
	}

	private void unmatched(String date, long amount, String txId, String depositor) {
		payments.register(new Payments.Deposit(null, Payments.Channel.BANK_TRANSFER, parse(date), Money.won(amount),
				depositor, txId));
	}
}
