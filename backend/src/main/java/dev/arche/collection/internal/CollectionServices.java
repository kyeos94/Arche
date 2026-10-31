package dev.arche.collection.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.collection.CollectionDaily;
import dev.arche.collection.ContactQuotaGuard;
import dev.arche.collection.PromiseTracker;
import dev.arche.common.BusinessException;
import dev.arche.common.Money;
import dev.arche.common.RepaymentMethod;
import dev.arche.servicing.AccountRef;
import dev.arche.servicing.LimitationInfo;
import dev.arche.servicing.LoanAccounts;
import dev.arche.staff.CurrentStaff;
import dev.arche.staff.StaffDirectory;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

interface ContactLogRepository extends JpaRepository<ContactLog, Long> {

	List<ContactLog> findByAccountIdOrderByIdDesc(long accountId);

	List<ContactLog> findByAccountIdAndContactDateBetween(long accountId, LocalDate from, LocalDate to);
}

interface PaymentPromiseRepository extends JpaRepository<PaymentPromise, Long> {

	List<PaymentPromise> findByAccountIdOrderByIdDesc(long accountId);

	List<PaymentPromise> findByAccountIdAndStatus(long accountId, PaymentPromise.Status status);
}

interface CollectionAlertRepository extends JpaRepository<CollectionAlert, Long> {

	boolean existsByDedupeKey(String dedupeKey);

	List<CollectionAlert> findTop200ByOrderByIdDesc();
}

/** 계약 조회 범위 검사 (정책서 9.1 담당 건 / 팀 전체) */
@Service
class AccountAccess {

	private final LoanAccounts accounts;
	private final StaffDirectory staff;

	AccountAccess(LoanAccounts accounts, StaffDirectory staff) {
		this.accounts = accounts;
		this.staff = staff;
	}

	AccountRef visible(long accountId) {
		AccountRef a = accounts.get(accountId);
		if (!staff.loanScope().allows(a.assignedStaffId())) {
			throw new BusinessException("FORBIDDEN", "담당 범위 밖의 계약입니다");
		}
		return a;
	}
}

/**
 * 추심 연락 기록과 판정 (정책서 6장, G14): 소멸시효 완성 채권 차단 → 연락 가능 시간(08:00~21:00) → 직전 7일 7회 한도.
 * 법정·계약상 통지는 한도·시간 제한에서 제외한다.
 */
@Service
class ContactService {

	static final LocalTime OPEN = LocalTime.of(8, 0);
	static final LocalTime CLOSE = LocalTime.of(21, 0);

	record Decision(long id, LocalDate contactDate, String result, int countInWindow, @Nullable String reason) {
	}

	private final ContactLogRepository logs;
	private final DeferralService deferrals;
	private final LoanAccounts accounts;
	private final BusinessDates businessDates;
	private final StaffDirectory staff;
	private final Clock clock;

	ContactService(ContactLogRepository logs, DeferralService deferrals, LoanAccounts accounts,
			BusinessDates businessDates, StaffDirectory staff, Clock clock) {
		this.logs = logs;
		this.deferrals = deferrals;
		this.accounts = accounts;
		this.businessDates = businessDates;
		this.staff = staff;
		this.clock = clock;
	}

	/** 판정 결과. contactDate는 예약이면 실제 연락될 날 */
	record Verdict(LocalDate contactDate, ContactLog.Result result, int count, @Nullable String reason) {
	}

	@Transactional
	Decision record(long accountId, ContactQuotaGuard.Channel channel, String memo) {
		OffsetDateTime now = OffsetDateTime.now(clock);
		CurrentStaff me = staff.current();
		Verdict v = decide(accountId, channel, businessDates.today(), now.toLocalTime());
		ContactLog log = logs.save(new ContactLog(accountId, v.contactDate(), now, channel, v.result(), v.count(),
				v.reason(), memo, me.id(), me.loginId()));
		return new Decision(log.getId(), v.contactDate(), v.result().name(), v.count(), v.reason());
	}

	/** 자동 문자 등 시스템 연락 기록 (판정은 호출자가 decide로 먼저 한다) */
	@Transactional
	ContactLog recordSystem(long accountId, LocalDate contactDate, ContactQuotaGuard.Channel channel, int count,
			String memo) {
		return logs.save(new ContactLog(accountId, contactDate, OffsetDateTime.now(clock), channel,
				ContactLog.Result.ALLOWED, count, null, memo, null, "system"));
	}

	/**
	 * 연락 판정 순서: 법정·계약상 통지는 항상 허용 → 소멸시효 완성 → 연락 유예 → 연락 가능 시간(문자는 예약) → 7일 7회.
	 */
	@Transactional(readOnly = true)
	Verdict decide(long accountId, ContactQuotaGuard.Channel channel, LocalDate today, LocalTime time) {
		if (channel == ContactQuotaGuard.Channel.STATUTORY_NOTICE) {
			return new Verdict(today, ContactLog.Result.ALLOWED, 0, null);
		}
		LocalDate contactDate = today;
		ContactLog.Result result = ContactLog.Result.ALLOWED;
		String reason = null;
		LimitationInfo limitation = accounts.limitation(accountId);
		ContactDeferral deferral = deferrals.activeOn(accountId, today).orElse(null);
		if (limitation.completed()) {
			result = ContactLog.Result.BLOCKED;
			reason = "소멸시효가 완성된 채권(" + limitation.completionDate() + ")은 추심 연락을 할 수 없습니다";
		} else if (deferral != null) {
			result = ContactLog.Result.BLOCKED;
			reason = "연락 유예 중입니다 (" + deferral.startDate() + " ~ " + deferral.endDate() + ", " + deferral.reason()
					+ ")";
		} else if (time.isBefore(OPEN) || !time.isBefore(CLOSE)) {
			if (channel == ContactQuotaGuard.Channel.SMS) {
				result = ContactLog.Result.SCHEDULED;
				contactDate = time.isBefore(OPEN) ? today : today.plusDays(1);
				reason = "연락 가능 시간 밖이라 " + contactDate + " 08:00으로 예약";
			} else {
				result = ContactLog.Result.BLOCKED;
				reason = "연락 가능 시간(08:00~21:00)이 아닙니다";
			}
		}
		int count = countInWindow(accountId, contactDate);
		if (result != ContactLog.Result.BLOCKED) {
			ContactQuotaGuard.Decision d = new ContactQuotaGuard(priorDates(accountId, contactDate))
					.attempt(new ContactQuotaGuard.Attempt(contactDate, channel));
			if (!d.allowed()) {
				result = ContactLog.Result.BLOCKED;
				reason = "직전 7일(당일 포함) 추심 연락 7회를 모두 썼습니다";
			}
		}
		return new Verdict(contactDate, result, count, reason);
	}

	int remaining(long accountId, LocalDate date) {
		return Math.max(0, ContactQuotaGuard.LIMIT - countInWindow(accountId, date));
	}

	private int countInWindow(long accountId, LocalDate date) {
		return priorDates(accountId, date).size();
	}

	private List<LocalDate> priorDates(long accountId, LocalDate date) {
		return logs.findByAccountIdAndContactDateBetween(accountId,
				date.minusDays(ContactQuotaGuard.WINDOW_DAYS - 1), date).stream()
				.filter(ContactLog::countsTowardQuota).map(ContactLog::contactDate).toList();
	}
}

/**
 * 매입채권 분할납부 약속 (정책서 1-1장, R18, G19). 판정은 입금 내역으로 매번 다시 계산한다
 * (소급 입금·입금 취소가 있어도 같은 결과). 해제는 일마감에서 확정한다.
 */
@Service
class PromiseService {

	record JudgmentRow(int index, LocalDate promiseDate, LocalDate deadline, long paid, Boolean fulfilled,
			int consecutiveMisses, String action) {
	}

	private final PaymentPromiseRepository promises;
	private final LoanAccounts accounts;
	private final BusinessDates businessDates;

	PromiseService(PaymentPromiseRepository promises, LoanAccounts accounts, BusinessDates businessDates) {
		this.promises = promises;
		this.accounts = accounts;
		this.businessDates = businessDates;
	}

	@Transactional
	PaymentPromise register(AccountRef account, YearMonth firstMonth, int day, Money amount, int months,
			String memo) {
		if (!RepaymentMethod.ACQUIRED.name().equals(account.repaymentMethod())) {
			throw new BusinessException("PROMISE_INVALID", "분할납부 약속은 매입채권(N1)에만 등록합니다");
		}
		if (account.paidOffOn() != null) {
			throw new BusinessException("ACCOUNT_CLOSED", "완제된 계약입니다");
		}
		if (!promises.findByAccountIdAndStatus(account.id(), PaymentPromise.Status.ACTIVE).isEmpty()) {
			throw new BusinessException("PROMISE_EXISTS", "진행 중인 약속이 있습니다");
		}
		if (day < 1 || day > 31 || months < 1 || months > 60 || !amount.isPositive()) {
			throw new BusinessException("PROMISE_INVALID", "약속일(1~31), 회차(1~60), 금액을 확인하세요");
		}
		LocalDate today = businessDates.today();
		LocalDate first = firstMonth.atDay(Math.min(day, firstMonth.lengthOfMonth()));
		if (first.isBefore(today)) {
			throw new BusinessException("PROMISE_INVALID", "첫 약속일이 업무일자보다 이릅니다");
		}
		return promises.save(new PaymentPromise(account.id(), today, firstMonth, day, amount, months, memo));
	}

	/** judgeDate 전날까지 기한(약속일 다음 날)이 지난 약속만 판정 */
	List<PromiseTracker.Judgment> judge(PaymentPromise p, LocalDate judgeDate) {
		int due = 0;
		for (int k = 0; k < p.months(); k++) {
			YearMonth m = p.firstMonth().plusMonths(k);
			LocalDate deadline = m.atDay(Math.min(p.promiseDay(), m.lengthOfMonth())).plusDays(1);
			if (deadline.isBefore(judgeDate)) {
				due++;
			}
		}
		List<PromiseTracker.Deposit> deposits = accounts.payments(p.accountId()).stream()
				.map(r -> new PromiseTracker.Deposit(r.bankDate(), r.amount())).toList();
		return PromiseTracker.judge(p.registeredOn(), p.firstMonth(), p.promiseDay(), p.amount(), due, deposits);
	}

	/** 화면용: 판정된 회차 + 아직 기한이 안 된 회차 */
	List<JudgmentRow> rows(PaymentPromise p) {
		List<PromiseTracker.Judgment> judged = judge(p, businessDates.today());
		List<JudgmentRow> rows = new ArrayList<>();
		for (int k = 0; k < p.months(); k++) {
			YearMonth m = p.firstMonth().plusMonths(k);
			LocalDate date = m.atDay(Math.min(p.promiseDay(), m.lengthOfMonth()));
			if (k < judged.size()) {
				PromiseTracker.Judgment j = judged.get(k);
				rows.add(new JudgmentRow(k + 1, date, date.plusDays(1), j.paid().won(), j.fulfilled(),
						j.consecutiveMisses(), j.action().name()));
			} else if (p.status() == PaymentPromise.Status.ACTIVE || p.status() == PaymentPromise.Status.COMPLETED) {
				rows.add(new JudgmentRow(k + 1, date, date.plusDays(1), 0, null, 0, "PENDING"));
			}
		}
		return rows;
	}

	List<PaymentPromise> of(long accountId) {
		return promises.findByAccountIdOrderByIdDesc(accountId);
	}
}

/** 일마감 추심 배치 (R24 ④ 다음): 소멸시효 임박·완성 알림, 약속 불이행 알림과 해제 */
@Service
class DefaultCollectionDaily implements CollectionDaily {

	private final LoanAccounts accounts;
	private final PromiseService promiseService;
	private final PaymentPromiseRepository promises;
	private final CollectionAlertRepository alerts;
	private final AutoMessageService autoMessages;
	private final Clock clock;

	DefaultCollectionDaily(LoanAccounts accounts, PromiseService promiseService, PaymentPromiseRepository promises,
			CollectionAlertRepository alerts, AutoMessageService autoMessages, Clock clock) {
		this.accounts = accounts;
		this.autoMessages = autoMessages;
		this.promiseService = promiseService;
		this.promises = promises;
		this.alerts = alerts;
		this.clock = clock;
	}

	@Override
	@Transactional
	public Report run(LocalDate judgeDate) {
		int limitation = 0;
		int misses = 0;
		int terminated = 0;
		for (AccountRef a : accounts.openAccounts()) {
			LimitationInfo info = accounts.limitation(a.id());
			LocalDate completion = info.completionDate();
			if (completion != null) {
				if (completion.isBefore(judgeDate)) {
					limitation += alert(a, CollectionAlert.Type.LIMITATION_COMPLETED, "LIMC-" + a.id() + "-" + completion,
							a.accountNo() + " 소멸시효 완성(" + completion + "). 추심 연락이 차단됩니다", judgeDate);
				} else {
					LocalDate threshold = info.alertDates().stream().filter(d -> !d.isAfter(judgeDate))
							.max(LocalDate::compareTo).orElse(null);
					if (threshold != null) {
						limitation += alert(a, CollectionAlert.Type.LIMITATION_IMMINENT,
								"LIMI-" + a.id() + "-" + completion + "-" + threshold,
								a.accountNo() + " 소멸시효 완성 예정 " + completion + " (D-"
										+ ChronoUnit.DAYS.between(judgeDate, completion) + ")",
								judgeDate);
					}
				}
			}
			for (PaymentPromise p : promises.findByAccountIdAndStatus(a.id(), PaymentPromise.Status.ACTIVE)) {
				List<PromiseTracker.Judgment> judged = promiseService.judge(p, judgeDate);
				for (int k = p.judgedCount(); k < judged.size(); k++) {
					PromiseTracker.Judgment j = judged.get(k);
					if (j.fulfilled()) {
						continue;
					}
					if (j.action() == PromiseTracker.Action.TERMINATE) {
						p.terminate(judgeDate);
						terminated++;
						alert(a, CollectionAlert.Type.PROMISE_TERMINATED, "PRT-" + p.getId(),
								a.accountNo() + " 분할납부 약속 3회 연속 불이행 → 약속 해제, 법조치 검토 대상", judgeDate);
					} else {
						misses += alert(a, CollectionAlert.Type.PROMISE_MISSED, "PRM-" + p.getId() + "-" + k,
								a.accountNo() + " " + j.promiseDate() + " 약속 불이행 (입금 " + j.paid() + ", 연속 "
										+ j.consecutiveMisses() + "회)",
								judgeDate);
					}
				}
				p.judged(judged.size());
				if (p.status() == PaymentPromise.Status.ACTIVE && judged.size() == p.months()) {
					p.complete();
				}
			}
		}
		return new Report(limitation, misses, terminated, autoMessages.generate(judgeDate));
	}

	private int alert(AccountRef a, CollectionAlert.Type type, String key, String message, LocalDate date) {
		if (alerts.existsByDedupeKey(key)) {
			return 0;
		}
		alerts.save(new CollectionAlert(a.id(), a.assignedStaffId(), type, key, message, date,
				OffsetDateTime.now(clock)));
		return 1;
	}
}
