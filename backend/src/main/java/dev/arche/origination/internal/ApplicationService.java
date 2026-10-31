package dev.arche.origination.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.common.BusinessException;
import dev.arche.common.InterestRate;
import dev.arche.common.Money;
import dev.arche.common.jpa.NumberGenerator;
import dev.arche.customer.CustomerView;
import dev.arche.customer.Customers;
import dev.arche.origination.ApplicationStatus;
import dev.arche.origination.LoanDisbursed;
import dev.arche.product.ProductCatalog;
import dev.arche.product.ProductView;
import dev.arche.staff.CurrentStaff;
import dev.arche.staff.Permission;
import dev.arche.staff.StaffDirectory;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

interface LoanApplicationRepository extends JpaRepository<LoanApplication, Long> {

	List<LoanApplication> findAllByOrderByIdDesc();
}

interface ApplicationStatusHistoryRepository extends JpaRepository<ApplicationStatusHistory, Long> {

	List<ApplicationStatusHistory> findByApplicationIdOrderById(long applicationId);
}

/**
 * 신청 → 심사 → 승인 → 약정 → 실행 (MVP 한 줄기). 실행 시 LoanDisbursed 이벤트를 발행하고,
 * Spring Modulith가 같은 트랜잭션에서 발행 기록을 남겨 유실을 막는다 (Transactional Outbox).
 */
@Service
class ApplicationService {

	/** 심사 승인 전결: 1,000만원 이하 심사역, 초과는 심사팀장 (정책서 7장) */
	static final Money APPROVAL_THRESHOLD = Money.won(10_000_000);
	static final int MIN_AGE = 19;

	private final LoanApplicationRepository applications;
	private final ApplicationStatusHistoryRepository history;
	private final ProductCatalog products;
	private final Customers customers;
	private final StaffDirectory staff;
	private final BusinessDates businessDates;
	private final NumberGenerator numbers;
	private final ApplicationEventPublisher events;
	private final Clock clock;

	ApplicationService(LoanApplicationRepository applications, ApplicationStatusHistoryRepository history,
			ProductCatalog products, Customers customers, StaffDirectory staff, BusinessDates businessDates,
			NumberGenerator numbers, ApplicationEventPublisher events, Clock clock) {
		this.applications = applications;
		this.history = history;
		this.products = products;
		this.customers = customers;
		this.staff = staff;
		this.businessDates = businessDates;
		this.numbers = numbers;
		this.events = events;
		this.clock = clock;
	}

	@Transactional
	LoanApplication receive(long customerId, String productCode, long amount, int termMonths, int graceMonths,
			int dueDay) {
		products.current(productCode);
		customers.get(customerId);
		LoanApplication a = applications.save(new LoanApplication(numbers.next("application_no_seq", "A"),
				customerId, productCode, amount, termMonths, graceMonths, dueDay));
		record(a, null, ApplicationStatus.RECEIVED, "접수");
		return a;
	}

	@Transactional
	LoanApplication transition(long id, ApplicationStatus next, String reason) {
		LoanApplication a = find(id);
		if (next == ApplicationStatus.APPROVED || next == ApplicationStatus.DISBURSED) {
			throw new BusinessException("TRANSITION_INVALID", "승인·실행은 전용 API를 사용하세요");
		}
		if (next == ApplicationStatus.REJECTED && (reason == null || reason.isBlank())) {
			throw new BusinessException("REASON_REQUIRED", "거절 사유가 필요합니다");
		}
		if (next == ApplicationStatus.PRE_SCREENING && autoRejected(a)) {
			return a;
		}
		if (next == ApplicationStatus.ASSIGNED) {
			a.assign(staff.current().id());
		}
		ApplicationStatus from = a.moveTo(next);
		record(a, from, next, reason);
		return a;
	}

	/** 자동 거절: 만 19세 미만 (정책서 7장). 신용조회·거절 이력 규칙은 확장 지점 */
	private boolean autoRejected(LoanApplication a) {
		CustomerView c = customers.get(a.customerId());
		if (Period.between(c.birthDate(), businessDates.today()).getYears() >= MIN_AGE) {
			return false;
		}
		ApplicationStatus from = a.moveTo(ApplicationStatus.REJECTED);
		record(a, from, ApplicationStatus.REJECTED, "자동 거절: 만 19세 미만");
		return true;
	}

	@Transactional
	LoanApplication approve(long id, long amount, BigDecimal rate) {
		LoanApplication a = find(id);
		CurrentStaff me = staff.current();
		Permission needed = Money.won(amount).isGreaterThan(APPROVAL_THRESHOLD) ? Permission.APPLICATION_APPROVE_LARGE
				: Permission.APPLICATION_APPROVE_SMALL;
		if (!me.has(needed)) {
			throw new BusinessException("FORBIDDEN", "승인 전결 권한이 없습니다 (1,000만원 초과는 심사팀장)");
		}
		ProductView product = products.current(a.productCode());
		product.validate(Money.won(amount), a.termMonths(), a.graceMonths(), new InterestRate(rate));
		a.approve(amount, rate);
		ApplicationStatus from = a.moveTo(ApplicationStatus.APPROVED);
		record(a, from, ApplicationStatus.APPROVED, "승인 " + amount + "원, " + rate + "%");
		return a;
	}

	@Transactional
	LoanApplication disburse(long id) {
		LoanApplication a = find(id);
		LocalDate today = businessDates.today();
		ApplicationStatus from = a.moveTo(ApplicationStatus.DISBURSED);
		a.disbursed(today);
		record(a, from, ApplicationStatus.DISBURSED, "실행 (송금 확인)");
		events.publishEvent(new LoanDisbursed(a.getId(), a.applicationNo(), a.customerId(), a.productCode(),
				a.approvedAmount(), a.approvedRate(), a.termMonths(), a.graceMonths(), a.dueDay(), today));
		return a;
	}

	List<ApplicationStatusHistory> history(long id) {
		return history.findByApplicationIdOrderById(id);
	}

	LoanApplication find(long id) {
		return applications.findById(id)
				.orElseThrow(() -> new BusinessException("APPLICATION_NOT_FOUND", "신청이 없습니다"));
	}

	private void record(LoanApplication a, ApplicationStatus from, ApplicationStatus to, String reason) {
		String actor = staff.currentIfAny().map(CurrentStaff::loginId).orElse("system");
		history.save(new ApplicationStatusHistory(a.getId(), from == null ? null : from.name(), to.name(), actor,
				reason, OffsetDateTime.now(clock)));
	}
}
