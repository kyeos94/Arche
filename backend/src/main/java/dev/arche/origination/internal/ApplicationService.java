package dev.arche.origination.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.common.BusinessException;
import dev.arche.common.InterestRate;
import dev.arche.common.Money;
import dev.arche.common.jpa.NumberGenerator;
import dev.arche.customer.Customers;
import dev.arche.origination.ApplicationDecided;
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
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

interface LoanApplicationRepository extends JpaRepository<LoanApplication, Long> {

	List<LoanApplication> findAllByOrderByIdDesc();

	List<LoanApplication> findByStatusOrderByIdDesc(ApplicationStatus status);

	List<LoanApplication> findByCustomerId(long customerId);

	Optional<LoanApplication> findByIntakeSourceAndExternalRef(String intakeSource, String externalRef);

	Optional<LoanApplication> findByApplicationNo(String applicationNo);

	List<LoanApplication> findByUnderwriterIdAndStatusInOrderById(Long underwriterId,
			java.util.Collection<ApplicationStatus> statuses);
}

interface ApplicationStatusHistoryRepository extends JpaRepository<ApplicationStatusHistory, Long> {

	List<ApplicationStatusHistory> findByApplicationIdOrderById(long applicationId);
}

/**
 * 신청 → 사전심사(자동 거절) → 배정 → 심사(서류) → 승인 → 약정 → 실행. 실행 시 LoanDisbursed 이벤트를 발행하고,
 * Spring Modulith가 같은 트랜잭션에서 발행 기록을 남겨 유실을 막는다 (Transactional Outbox).
 */
@Service
class ApplicationService implements dev.arche.origination.UnderwriterAssignments {

	/** 심사자가 아직 책임지는 신청 상태 (R75) */
	static final List<ApplicationStatus> OPEN_FOR_UNDERWRITER = List.of(ApplicationStatus.ASSIGNED,
			ApplicationStatus.UNDERWRITING, ApplicationStatus.APPROVED, ApplicationStatus.CONTRACTED);

	private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);

	/** 심사 승인 전결: 1,000만원 이하 심사역, 초과는 심사팀장 (정책서 7장) */
	static final Money APPROVAL_THRESHOLD = Money.won(10_000_000);
	static final String MANUAL_REJECT_CODE = "UNDERWRITING_DECLINE";

	private final LoanApplicationRepository applications;
	private final ApplicationStatusHistoryRepository history;
	private final ScreeningService screening;
	private final DocumentService documents;
	private final AssignmentService assignment;
	private final BrokerService brokers;
	private final ProductCatalog products;
	private final Customers customers;
	private final StaffDirectory staff;
	private final BusinessDates businessDates;
	private final NumberGenerator numbers;
	private final ApplicationEventPublisher events;
	private final Clock clock;

	ApplicationService(LoanApplicationRepository applications, ApplicationStatusHistoryRepository history,
			ScreeningService screening, DocumentService documents, AssignmentService assignment,
			BrokerService brokers, ProductCatalog products, Customers customers, StaffDirectory staff,
			BusinessDates businessDates, NumberGenerator numbers, ApplicationEventPublisher events, Clock clock) {
		this.applications = applications;
		this.history = history;
		this.screening = screening;
		this.documents = documents;
		this.assignment = assignment;
		this.brokers = brokers;
		this.products = products;
		this.customers = customers;
		this.staff = staff;
		this.businessDates = businessDates;
		this.numbers = numbers;
		this.events = events;
		this.clock = clock;
	}

	/**
	 * 모든 채널의 접수 (guide 2.1). 같은 채널 신청 번호로 다시 오면 기존 신청을 돌려준다(재전송 안전).
	 * 직원 입력이 아닌 채널은 사전심사·배정까지 자동으로 진행한다 (R57).
	 */
	@Transactional
	LoanApplication intake(ApplicationIntakeCommand c) {
		if (c.intakeSource() != null && c.externalRef() != null) {
			Optional<LoanApplication> existing = applications.findByIntakeSourceAndExternalRef(c.intakeSource(),
					c.externalRef());
			if (existing.isPresent()) {
				return existing.get();
			}
		}
		ProductView product = products.current(c.productCode());
		if ("N1".equals(product.code())) {
			throw new BusinessException("PRODUCT_NOT_APPLICABLE", "매입채권 상품은 신청을 받지 않습니다");
		}
		product.validate(Money.won(c.requestedAmount()), c.termMonths(), c.graceMonths(), product.defaultRate());
		customers.get(c.customerId());
		LoanApplication a = applications.save(new LoanApplication(numbers.next("application_no_seq", "A"), c));
		record(a, null, ApplicationStatus.RECEIVED, "접수 (" + channelLabel(c.channel()) + ")");
		if (c.consent() != null) {
			screening.recordConsent(a, c.consent().channel(), c.consent().agreedOn(), c.consent().evidence());
		}
		documents.createChecklist(a);
		if (c.channel() != LoanApplication.Channel.STAFF) {
			autoProceed(a);
		}
		return a;
	}

	/** 비대면 채널: 사전심사 → 통과하면 자동 배정. 배정할 심사역이 없으면 사전심사 상태로 둔다 */
	private void autoProceed(LoanApplication a) {
		if (preScreen(a)) {
			return;
		}
		Optional<CurrentStaff> chosen = assignment.assignAuto(a);
		if (chosen.isEmpty()) {
			log.warn("신청 {} 자동 배정 보류: {} 담당 심사역 없음", a.applicationNo(), a.productCode());
			return;
		}
		ApplicationStatus from = a.moveTo(ApplicationStatus.ASSIGNED);
		record(a, from, ApplicationStatus.ASSIGNED,
				"자동 배정 (" + assignment.strategyCode() + ") " + chosen.get().name());
	}

	@Transactional
	LoanApplication transition(long id, ApplicationStatus next, @Nullable String reason, @Nullable String reasonCode,
			@Nullable Long underwriterId) {
		LoanApplication a = find(id);
		if (next == ApplicationStatus.APPROVED || next == ApplicationStatus.DISBURSED) {
			throw new BusinessException("TRANSITION_INVALID", "승인·실행은 전용 API를 사용하세요");
		}
		if (next == ApplicationStatus.REJECTED && (reason == null || reason.isBlank())) {
			throw new BusinessException("REASON_REQUIRED", "거절 사유가 필요합니다");
		}
		if (next == ApplicationStatus.PRE_SCREENING) {
			preScreen(a);
			return a;
		}
		if (next == ApplicationStatus.ASSIGNED) {
			if (!a.status().canMoveTo(ApplicationStatus.ASSIGNED)) {
				throw new BusinessException("TRANSITION_INVALID", a.status() + " → ASSIGNED 전이는 허용되지 않습니다");
			}
			String how;
			if (underwriterId != null) {
				requireAssignPermission();
				how = "수동 배정 " + assignment.assignManual(a, underwriterId).name();
			} else {
				how = "자동 배정 (" + assignment.strategyCode() + ") " + assignment.assignAuto(a)
						.orElseThrow(() -> AssignmentService.noUnderwriter(a.productCode())).name();
			}
			ApplicationStatus from = a.moveTo(ApplicationStatus.ASSIGNED);
			record(a, from, ApplicationStatus.ASSIGNED, reason == null || reason.isBlank() ? how : how + " — " + reason);
			return a;
		}
		ApplicationStatus from = a.moveTo(next);
		if (next == ApplicationStatus.REJECTED) {
			a.rejected(reasonCode == null || reasonCode.isBlank() ? MANUAL_REJECT_CODE : reasonCode,
					businessDates.today());
		}
		record(a, from, next, reason);
		if (next == ApplicationStatus.REJECTED || next == ApplicationStatus.WITHDRAWN) {
			decided(a);
		}
		return a;
	}

	/**
	 * 사전심사 진입 + 자동 거절 규칙 (R53). 거절이면 바로 REJECTED로 보내고 true
	 */
	private boolean preScreen(LoanApplication a) {
		ApplicationStatus from = a.moveTo(ApplicationStatus.PRE_SCREENING);
		record(a, from, ApplicationStatus.PRE_SCREENING, "사전심사");
		List<ScreeningRules.Result> results = screening.screen(a);
		List<ScreeningRules.Result> rejected = results.stream().filter(ScreeningRules.Result::rejected).toList();
		if (rejected.isEmpty()) {
			return false;
		}
		a.moveTo(ApplicationStatus.REJECTED);
		a.rejected(rejected.getFirst().rule().name(), businessDates.today());
		record(a, ApplicationStatus.PRE_SCREENING, ApplicationStatus.REJECTED, "자동 거절: "
				+ rejected.stream().map(r -> r.rule().label).collect(Collectors.joining(", ")));
		decided(a);
		return true;
	}

	/** 배정된 신청의 심사자 바꾸기 (심사팀장·본부장, R55). 상태는 그대로 두고 이력에 남긴다 */
	@Transactional
	LoanApplication reassign(long id, long underwriterId, String reason) {
		requireAssignPermission();
		LoanApplication a = find(id);
		if (a.status() != ApplicationStatus.ASSIGNED && a.status() != ApplicationStatus.UNDERWRITING) {
			throw new BusinessException("APPLICATION_STATE", "배정·심사 중인 신청만 재배정할 수 있습니다");
		}
		if (reason == null || reason.isBlank()) {
			throw new BusinessException("REASON_REQUIRED", "재배정 사유를 입력하세요");
		}
		Long before = a.underwriterId();
		String to = assignment.assignManual(a, underwriterId).name();
		String fromName = before == null ? "-" : staff.byId(before).map(CurrentStaff::name).orElse("#" + before);
		record(a, a.status(), a.status(), "재배정 " + fromName + " → " + to + ": " + reason);
		return a;
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
		List<DocumentType> missing = documents.missingRequired(a.getId());
		if (!missing.isEmpty()) {
			throw new BusinessException("DOCUMENTS_INCOMPLETE", "확인되지 않은 필수 서류가 있습니다: "
					+ missing.stream().map(d -> d.label).collect(Collectors.joining(", ")));
		}
		ApplicationStatus from = a.moveTo(ApplicationStatus.APPROVED);
		a.approve(amount, rate, businessDates.today());
		record(a, from, ApplicationStatus.APPROVED, "승인 " + amount + "원, " + rate + "%");
		decided(a);
		return a;
	}

	@Transactional
	LoanApplication disburse(long id) {
		LoanApplication a = find(id);
		LocalDate today = businessDates.today();
		ApplicationStatus from = a.moveTo(ApplicationStatus.DISBURSED);
		a.disbursed(today);
		record(a, from, ApplicationStatus.DISBURSED, "실행 (송금 확인)");
		brokers.settle(a, today);
		events.publishEvent(new LoanDisbursed(a.getId(), a.applicationNo(), a.customerId(), a.productCode(),
				a.approvedAmount(), a.approvedRate(), a.termMonths(), a.graceMonths(), a.dueDay(), today));
		return a;
	}

	@Override
	@Transactional(readOnly = true)
	public int openCount(long staffId) {
		return applications.findByUnderwriterIdAndStatusInOrderById(staffId, OPEN_FOR_UNDERWRITER).size();
	}

	@Override
	@Transactional
	public int reassignOpen(long fromStaffId, long toStaffId, String reason) {
		List<LoanApplication> open = applications.findByUnderwriterIdAndStatusInOrderById(fromStaffId,
				OPEN_FOR_UNDERWRITER);
		for (LoanApplication a : open) {
			String to;
			try {
				to = assignment.assignManual(a, toStaffId).name();
			} catch (BusinessException e) {
				throw new BusinessException(e.code(), a.applicationNo() + ": " + e.getMessage());
			}
			record(a, a.status(), a.status(), "퇴사 이관 → " + to + ": " + reason);
		}
		return open.size();
	}

	List<ApplicationStatusHistory> history(long id) {
		return history.findByApplicationIdOrderById(id);
	}

	LoanApplication find(long id) {
		return applications.findById(id)
				.orElseThrow(() -> new BusinessException("APPLICATION_NOT_FOUND", "신청이 없습니다"));
	}

	/** 결과 확정 이벤트 (플랫폼 결과 통보 등). 커밋 후 비동기로 전달된다 */
	private void decided(LoanApplication a) {
		events.publishEvent(new ApplicationDecided(a.getId(), a.applicationNo(), a.status().name()));
	}

	private void requireAssignPermission() {
		if (!staff.current().has(Permission.APPLICATION_ASSIGN)) {
			throw new BusinessException("FORBIDDEN", "심사자 수동 배정은 심사팀장·본부장만 할 수 있습니다 (R55)");
		}
	}

	static String channelLabel(LoanApplication.Channel c) {
		return switch (c) {
			case STAFF -> "직원 입력";
			case BROKER -> "중개업자";
			case PLATFORM -> "대출 비교 플랫폼";
		};
	}

	private void record(LoanApplication a, ApplicationStatus from, ApplicationStatus to, String reason) {
		String actor = staff.currentIfAny().map(CurrentStaff::loginId).orElse("system");
		String text = reason != null && reason.length() > 200 ? reason.substring(0, 200) : reason;
		history.save(new ApplicationStatusHistory(a.getId(), from == null ? null : from.name(), to.name(), actor,
				text, OffsetDateTime.now(clock)));
	}
}
