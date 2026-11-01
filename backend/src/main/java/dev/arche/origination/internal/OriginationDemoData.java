package dev.arche.origination.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.common.jpa.NumberGenerator;
import dev.arche.customer.CustomerView;
import dev.arche.customer.Customers;
import dev.arche.origination.ApplicationStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 심사 화면 데모 데이터 (arche.demo.enabled=true일 때만, 여러 번 실행해도 한 번만 만든다).
 * <ul>
 * <li>데모 중개업자: API 키 {@value #DEMO_BROKER_KEY} (로컬 데모 전용), 수수료율 3.00%</li>
 * <li>직원 입력 신청(동의 있음, 접수 상태) · 중개업자 API 신청(자동 사전심사·배정) · 신용조회 연체로 자동 거절된 신청</li>
 * </ul>
 * 고객 식별번호는 가상 값이며 Mock 신용조회는 끝자리로 등급을 정한다.
 */
@Component
@Order(15)
@ConditionalOnProperty(name = "arche.demo.enabled", havingValue = "true")
class OriginationDemoData implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(OriginationDemoData.class);
	static final String DEMO_BROKER_REG = "DEMO-BROKER-0001";
	static final String DEMO_BROKER_KEY = "brk_demo_local_only";

	private final BrokerRepository brokers;
	private final BrokerCommissionRateRepository rates;
	private final LoanApplicationRepository applications;
	private final ApplicationService service;
	private final Customers customers;
	private final BusinessDates businessDates;
	private final NumberGenerator numbers;

	OriginationDemoData(BrokerRepository brokers, BrokerCommissionRateRepository rates,
			LoanApplicationRepository applications, ApplicationService service, Customers customers,
			BusinessDates businessDates, NumberGenerator numbers) {
		this.brokers = brokers;
		this.rates = rates;
		this.applications = applications;
		this.service = service;
		this.customers = customers;
		this.businessDates = businessDates;
		this.numbers = numbers;
	}

	@Override
	public void run(ApplicationArguments args) {
		try {
			seed();
		} catch (RuntimeException e) {
			// 데모 데이터 실패로 기동을 막지 않는다
			log.warn("심사 데모 데이터를 만들지 못했습니다: {}", e.getMessage());
		}
	}

	void seed() {
		LocalDate today = businessDates.today();
		Broker broker = brokers.findAll().stream().filter(b -> DEMO_BROKER_REG.equals(b.registrationNo())).findFirst()
				.orElseGet(() -> {
					Broker b = brokers.save(new Broker(numbers.next("broker_no_seq", "B"), "가상중개 데모",
							DEMO_BROKER_REG, BrokerService.hash(DEMO_BROKER_KEY), null));
					rates.save(new BrokerCommissionRate(b.getId(), new BigDecimal("3.00"), today, "데모 등록"));
					return b;
				});

		CustomerView waiting = customer("심사대기", "880808-1000013");
		if (applications.findByCustomerId(waiting.id()).isEmpty()) {
			service.intake(command(LoanApplication.Channel.STAFF, null, null, null, waiting.id(), "P1", 8_000_000,
					24, consent(ApplicationConsent.Channel.STAFF, today, "데모 서면 동의서")));
		}

		CustomerView brokered = customer("중개접수", "880808-2000025");
		if (applications.findByIntakeSourceAndExternalRef(broker.brokerNo(), "DEMO-BR-1").isEmpty()) {
			service.intake(command(LoanApplication.Channel.BROKER, broker.brokerNo(), "DEMO-BR-1", broker.getId(),
					brokered.id(), "P5", 5_000_000, 12, consent(ApplicationConsent.Channel.BROKER, today,
							"BROKER-" + broker.brokerNo() + "-DEMO")));
		}

		CustomerView delinquent = customer("연체이력", "880808-1000039");
		if (applications.findByCustomerId(delinquent.id()).isEmpty()) {
			LoanApplication a = service.intake(command(LoanApplication.Channel.STAFF, null, null, null,
					delinquent.id(), "P2", 3_000_000, 6, consent(ApplicationConsent.Channel.STAFF, today, "데모 녹취")));
			service.transition(a.getId(), ApplicationStatus.PRE_SCREENING, null, null, null);
		}
		log.info("심사 데모 데이터 준비 (중개업자 {}, API 키 {})", broker.brokerNo(), DEMO_BROKER_KEY);
	}

	private CustomerView customer(String name, String residentNo) {
		return customers.registerOrFind(new Customers.NewCustomer(name, residentNo, LocalDate.of(1988, 8, 8),
				"010-8888-0000", "가상시 데모구"));
	}

	private static ApplicationIntakeCommand.Consent consent(ApplicationConsent.Channel channel, LocalDate on,
			String evidence) {
		return new ApplicationIntakeCommand.Consent(channel, on, evidence);
	}

	private static ApplicationIntakeCommand command(LoanApplication.Channel channel, String source, String ref,
			Long brokerId, long customerId, String product, long amount, int term,
			ApplicationIntakeCommand.Consent consent) {
		return new ApplicationIntakeCommand(channel, source, ref, brokerId, customerId, product, amount, term, 0, 25,
				consent);
	}
}
