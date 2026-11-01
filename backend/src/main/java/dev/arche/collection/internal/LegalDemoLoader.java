package dev.arche.collection.internal;

import dev.arche.calendar.BusinessDates;
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
 * 데모 데이터: 일괄 입금 배분(R47)·법조치(R51·R52) 시연용 (arche.demo.enabled=true, 한 번만).
 * 날짜는 기동 시점 업무일자 기준으로 잡는다.
 * <ul>
 * <li>고객 "정배분"의 자유상환 계약 2건(연체) + 그 고객 이름의 미확인 입금 → 미확인 입금 화면에서 "여러 계약에 배분"</li>
 * <li>첫 계약에 지급명령(신청·발령)과 인지대 비용 → 계약 상세 "법조치" 탭</li>
 * </ul>
 */
@Component
@Order(15)
@ConditionalOnProperty(name = "arche.demo.enabled", havingValue = "true")
class LegalDemoLoader implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(LegalDemoLoader.class);
	private static final String MARKER_RESIDENT_NO = "770404-1000047";

	private final Customers customers;
	private final LoanAccounts accounts;
	private final Payments payments;
	private final LegalActionService legal;
	private final StaffDirectory staff;
	private final BusinessDates businessDates;

	LegalDemoLoader(Customers customers, LoanAccounts accounts, Payments payments, LegalActionService legal,
			StaffDirectory staff, BusinessDates businessDates) {
		this.customers = customers;
		this.accounts = accounts;
		this.payments = payments;
		this.legal = legal;
		this.staff = staff;
		this.businessDates = businessDates;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (customers.findByResidentNo(MARKER_RESIDENT_NO).isPresent()) {
			return;
		}
		LocalDate today = businessDates.today();
		Long col1 = staff.byLoginId("col1").map(CurrentStaff::id).orElse(null);
		CustomerView c = customers.registerOrFind(new Customers.NewCustomer("정배분", MARKER_RESIDENT_NO,
				LocalDate.parse("1977-04-04"), "010-1234-0047", "서울시 가상구 예시로 47"));
		AccountRef first = accounts.open(new LoanAccounts.OpenCommand(c.id(), "P5", Money.won(5_000_000),
				InterestRate.of("19.5"), 12, 0, 10, today.minusDays(120), col1, null));
		AccountRef second = accounts.open(new LoanAccounts.OpenCommand(c.id(), "P5", Money.won(2_000_000),
				InterestRate.of("19.5"), 12, 0, 20, today.minusDays(75), col1, null));
		payments.register(new Payments.Deposit(null, Payments.Channel.BANK_TRANSFER, today, Money.won(300_000),
				"정배분", "DEMO-SPLIT-" + today));

		LegalAction order = legal.open(first, new LegalActionService.OpenCommand(LegalActionRules.Type.PAYMENT_ORDER,
				today.minusDays(10), "가상지방법원", "데모-지급명령-1", null, 5_000_000L, "데모: 이자 미납 장기화"));
		legal.move(order.getId(), LegalActionRules.Stage.ISSUED, today.minusDays(3), "데모");
		legal.charge(order.getId(), LegalActionCost.CostType.COURT_FEE, Money.won(25_000), today.minusDays(10),
				"인지대");
		log.info("데모 데이터(배분·법조치) 생성: 고객 {} 계약 {} {}, 법조치 {}", c.customerNo(), first.accountNo(),
				second.accountNo(), order.actionNo());
	}
}
