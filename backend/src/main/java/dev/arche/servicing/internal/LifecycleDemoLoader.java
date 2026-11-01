package dev.arche.servicing.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.common.InterestRate;
import dev.arche.common.Money;
import dev.arche.customer.CustomerView;
import dev.arche.customer.Customers;
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
 * 생애주기 데모 데이터 (arche.demo.enabled=true, R39~R41). 업무일자 기준 상대 날짜로 만들어 언제 기동해도 같은 모습이다.
 * <ul>
 * <li>계약 F (N1, 양수 200일 전, 입금 없음): 상각 후보 → "상각 후보" 화면에서 확정</li>
 * <li>계약 G (N1, 양수 400일 전): 상각 후 매각까지 된 상태 → 생애주기 탭에서 환매·매각 취소 요청, 입금 시 이전 대상</li>
 * </ul>
 * 매입채권은 양수 시점에 이미 기한이익상실이라 과거 입금 없이도 후보 조건을 만들 수 있다. 이미 있으면 건너뛴다(멱등).
 */
@Component
@Order(15)
@ConditionalOnProperty(name = "arche.demo.enabled", havingValue = "true")
class LifecycleDemoLoader implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(LifecycleDemoLoader.class);
	private static final String MARKER_RESIDENT_NO = "700101-1000039";

	private final Customers customers;
	private final LoanAccounts accounts;
	private final LifecycleService lifecycle;
	private final StaffDirectory staff;
	private final BusinessDates businessDates;

	LifecycleDemoLoader(Customers customers, LoanAccounts accounts, LifecycleService lifecycle,
			StaffDirectory staff, BusinessDates businessDates) {
		this.customers = customers;
		this.accounts = accounts;
		this.lifecycle = lifecycle;
		this.staff = staff;
		this.businessDates = businessDates;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (customers.findByResidentNo(MARKER_RESIDENT_NO).isPresent()) {
			return;
		}
		Long col1 = staff.byLoginId("col1").map(CurrentStaff::id).orElse(null);
		LocalDate today = businessDates.today();

		AccountRef f = acquire(customer("정상각", MARKER_RESIDENT_NO, "1970-01-01"), today.minusDays(200),
				3_000_000, col1);

		AccountRef g = acquire(customer("한매각", "720202-1000040", "1972-02-02"), today.minusDays(400), 2_500_000,
				col1);
		lifecycle.chargeOff(g.id(), "데모: 회수 불능 (가상)");
		lifecycle.sell(g.id(), new LifecycleService.SaleCommand("가상자산관리(가상)", Money.won(400_000),
				today.minusDays(3), "데모: 상각채권 포트폴리오 매각 (가상)"));

		log.info("생애주기 데모 데이터 생성: 상각 후보 {}, 매각 {}", f.accountNo(), g.accountNo());
	}

	private CustomerView customer(String name, String residentNo, String birth) {
		return customers.registerOrFind(new Customers.NewCustomer(name, residentNo, LocalDate.parse(birth),
				"010-5555-" + residentNo.substring(10), "서울시 가상구 예시로 2"));
	}

	private AccountRef acquire(CustomerView c, LocalDate acquiredOn, long principal, Long staffId) {
		LocalDate accelerated = acquiredOn.minusYears(1);
		return accounts.acquire(new LoanAccounts.AcquireCommand(c.id(), acquiredOn, Money.won(principal / 5),
				"가상캐피탈(가상)", acquiredOn.minusYears(3), InterestRate.of("15.0"), InterestRate.of("18.0"),
				Money.won(principal), Money.won(principal / 10), Money.ZERO, true, accelerated, accelerated, null,
				staffId));
	}
}
