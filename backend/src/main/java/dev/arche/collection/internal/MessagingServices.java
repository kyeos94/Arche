package dev.arche.collection.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.collection.ContactQuotaGuard;
import dev.arche.common.BusinessException;
import dev.arche.common.Money;
import dev.arche.integration.MessagingPort;
import dev.arche.servicing.LoanAccounts;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

interface ContactDeferralRepository extends JpaRepository<ContactDeferral, Long> {

	List<ContactDeferral> findByAccountIdOrderByIdDesc(long accountId);

	List<ContactDeferral> findByAccountIdAndStatus(long accountId, ContactDeferral.Status status);
}

interface OutboundMessageRepository extends JpaRepository<OutboundMessage, Long> {

	boolean existsByDedupeKey(String dedupeKey);

	List<OutboundMessage> findByStatusAndScheduledDateLessThanEqualOrderById(OutboundMessage.Status status,
			LocalDate date);

	List<OutboundMessage> findByAccountIdOrderByIdDesc(long accountId);

	List<OutboundMessage> findTop200ByOrderByIdDesc();
}

/**
 * 연락 유예 (정책서 6장): 최대 3개월, 1회 연장(연장도 최대 3개월). 한 계약에 진행 중인 유예는 하나.
 */
@Service
class DeferralService {

	private final ContactDeferralRepository deferrals;
	private final BusinessDates businessDates;

	DeferralService(ContactDeferralRepository deferrals, BusinessDates businessDates) {
		this.deferrals = deferrals;
		this.businessDates = businessDates;
	}

	Optional<ContactDeferral> activeOn(long accountId, LocalDate date) {
		return deferrals.findByAccountIdAndStatus(accountId, ContactDeferral.Status.ACTIVE).stream()
				.filter(d -> d.coversDate(date)).findFirst();
	}

	List<ContactDeferral> of(long accountId) {
		return deferrals.findByAccountIdOrderByIdDesc(accountId);
	}

	@Transactional
	ContactDeferral register(long accountId, LocalDate start, LocalDate end, String reason) {
		LocalDate today = businessDates.today();
		if (reason == null || reason.isBlank()) {
			throw new BusinessException("REASON_REQUIRED", "유예 사유(입원·재난 등)를 입력하세요");
		}
		if (start.isBefore(today)) {
			throw new BusinessException("DEFERRAL_INVALID", "유예 시작일은 업무일자 이후여야 합니다");
		}
		validateSpan(start, end);
		boolean overlapping = deferrals.findByAccountIdAndStatus(accountId, ContactDeferral.Status.ACTIVE).stream()
				.anyMatch(d -> !d.endDate().isBefore(today));
		if (overlapping) {
			throw new BusinessException("DEFERRAL_EXISTS", "진행 중인 연락 유예가 있습니다. 연장하거나 종료하세요");
		}
		return deferrals.save(new ContactDeferral(accountId, start, end, reason));
	}

	@Transactional
	ContactDeferral extend(long accountId, long deferralId, LocalDate newEnd) {
		ContactDeferral d = find(accountId, deferralId);
		if (d.status() != ContactDeferral.Status.ACTIVE) {
			throw new BusinessException("DEFERRAL_INVALID", "종료된 유예입니다");
		}
		if (d.extended()) {
			throw new BusinessException("DEFERRAL_EXTENDED", "연락 유예 연장은 1회만 할 수 있습니다");
		}
		if (!newEnd.isAfter(d.endDate())) {
			throw new BusinessException("DEFERRAL_INVALID", "연장 종료일은 현재 종료일 이후여야 합니다");
		}
		validateSpan(d.endDate(), newEnd);
		d.extend(newEnd);
		return d;
	}

	@Transactional
	ContactDeferral end(long accountId, long deferralId) {
		ContactDeferral d = find(accountId, deferralId);
		d.end(businessDates.today());
		return d;
	}

	private static void validateSpan(LocalDate from, LocalDate to) {
		if (to.isBefore(from)) {
			throw new BusinessException("DEFERRAL_INVALID", "종료일이 시작일보다 이릅니다");
		}
		if (to.isAfter(from.plusMonths(ContactDeferral.MAX_MONTHS))) {
			throw new BusinessException("DEFERRAL_TOO_LONG", "연락 유예는 한 번에 최대 3개월입니다");
		}
	}

	private ContactDeferral find(long accountId, long id) {
		return deferrals.findById(id).filter(d -> d.accountId() == accountId)
				.orElseThrow(() -> new BusinessException("DEFERRAL_NOT_FOUND", "유예가 없습니다"));
	}
}

/**
 * 자동 문자 (정책서 6장): 약정일 3일 전 납입 안내, 연체 1·7·30일째 연체 안내, 완제 안내 (+ 약속일 3일 전 약속 납부 안내).
 * 일마감이 판정일 08:00 발송분을 만들고, 발송기는 연락 가능 시간 안에서 발송 직전에 다시 판정한다.
 * 연체 안내만 추심 연락이라 7일 7회 한도·연락 유예·소멸시효 완성·약속 이행 중이면 보내지 않는다 (R38).
 */
@Service
class AutoMessageService {

	private static final Logger log = LoggerFactory.getLogger(AutoMessageService.class);
	static final String SENDER = "[예시대부(가상)] ";
	static final int REMINDER_DAYS_BEFORE = 3;
	static final List<Integer> DELINQUENCY_DAYS = List.of(1, 7, 30);

	record DispatchReport(int sent, int skipped) {
	}

	private final OutboundMessageRepository messages;
	private final PaymentPromiseRepository promises;
	private final ContactService contacts;
	private final CollectionStopService stops;
	private final LoanAccounts accounts;
	private final MessagingPort messaging;
	private final BusinessDates businessDates;
	private final Clock clock;

	AutoMessageService(OutboundMessageRepository messages, PaymentPromiseRepository promises,
			ContactService contacts, CollectionStopService stops, LoanAccounts accounts, MessagingPort messaging,
			BusinessDates businessDates, Clock clock) {
		this.messages = messages;
		this.promises = promises;
		this.contacts = contacts;
		this.stops = stops;
		this.accounts = accounts;
		this.messaging = messaging;
		this.businessDates = businessDates;
		this.clock = clock;
	}

	/** 일마감: judgeDate(D+1)에 보낼 문자를 만든다. 같은 사건은 한 번만 */
	@Transactional
	int generate(LocalDate judgeDate) {
		int created = 0;
		for (LoanAccounts.MessagingView v : accounts.messagingViews(judgeDate.minusDays(1))) {
			if (v.paidOffOn() != null) {
				if (v.paidOffOn().equals(judgeDate.minusDays(1))) {
					created += create(v, OutboundMessage.Type.PAYOFF, "PAYOFF-" + v.id(),
							v.accountNo() + " 계약이 " + v.paidOffOn() + "에 완제되었습니다. 감사합니다.", judgeDate);
				}
				continue;
			}
			boolean beforeDelinquency = "NORMAL".equals(v.status()) || "WORKOUT".equals(v.status());
			if (beforeDelinquency && v.nextDueDate() != null
					&& v.nextDueDate().equals(judgeDate.plusDays(REMINDER_DAYS_BEFORE))) {
				created += create(v, OutboundMessage.Type.PAYMENT_REMINDER, "REM-" + v.id() + "-" + v.nextDueDate(),
						v.accountNo() + " 납입일은 " + v.nextDueDate() + "이며 예정액은 "
								+ Money.won(v.nextPaymentAmount() == null ? 0 : v.nextPaymentAmount())
								+ "입니다. 입금 계좌는 계약별 가상계좌입니다.",
						judgeDate);
			}
			if (v.delinquencyStartedOn() != null) {
				// 연체 n일째: 납부 기한(휴일 유예 포함) 다음 날이 1일째 (R38)
				long nth = ChronoUnit.DAYS.between(v.delinquencyStartedOn(), judgeDate) + 1;
				if (DELINQUENCY_DAYS.contains((int) nth)) {
					OutboundMessage.Type type = OutboundMessage.Type.valueOf("DELINQUENCY_" + nth);
					created += create(v, type, "DLQ" + nth + "-" + v.id() + "-" + v.delinquencyStartedOn(),
							v.accountNo() + " " + v.delinquentSince() + " 납입분이 연체 " + nth
									+ "일째입니다. 연체이자가 늘어나니 납부해 주세요. 상담: 담당자",
							judgeDate);
				}
			}
			for (PaymentPromise p : promises.findByAccountIdAndStatus(v.id(), PaymentPromise.Status.ACTIVE)) {
				LocalDate target = judgeDate.plusDays(REMINDER_DAYS_BEFORE);
				for (int k = 0; k < p.months(); k++) {
					YearMonth m = p.firstMonth().plusMonths(k);
					LocalDate promiseDate = m.atDay(Math.min(p.promiseDay(), m.lengthOfMonth()));
					if (promiseDate.equals(target)) {
						created += create(v, OutboundMessage.Type.PROMISE_REMINDER, "PRR-" + p.getId() + "-" + k,
								v.accountNo() + " 약속하신 " + promiseDate + " 납부액은 " + p.amount() + "입니다.",
								judgeDate);
					}
				}
			}
		}
		return created;
	}

	private int create(LoanAccounts.MessagingView v, OutboundMessage.Type type, String key, String body,
			LocalDate date) {
		if (messages.existsByDedupeKey(key)) {
			return 0;
		}
		messages.save(new OutboundMessage(v.id(), v.customerId(), type, key, SENDER + body, date));
		return 1;
	}

	/** 연락 가능 시간(08:00~21:00)에 5분마다. 다중 서버에서는 ShedLock으로 한 대만 */
	@Scheduled(fixedDelayString = "${arche.messaging.dispatch-delay:PT5M}")
	@SchedulerLock(name = "auto-message-dispatch", lockAtMostFor = "PT10M")
	void scheduledDispatch() {
		try {
			dispatch();
		} catch (RuntimeException e) {
			log.error("자동 문자 발송 실패", e);
		}
	}

	@Transactional
	DispatchReport dispatch() {
		OffsetDateTime now = OffsetDateTime.now(clock);
		LocalTime time = now.toLocalTime();
		if (time.isBefore(ContactService.OPEN) || !time.isBefore(ContactService.CLOSE)) {
			return new DispatchReport(0, 0);
		}
		LocalDate today = businessDates.today();
		int sent = 0;
		int skipped = 0;
		for (OutboundMessage m : messages.findByStatusAndScheduledDateLessThanEqualOrderById(
				OutboundMessage.Status.SCHEDULED, today)) {
			String reason = null;
			int count = 0;
			// 매각 채권(R40)·추심 중지(R45)에는 완제 안내 말고는 아무것도 보내지 않는다 (납입·약속 안내 포함)
			if (m.type() != OutboundMessage.Type.PAYOFF) {
				reason = stops.blockReason(m.accountId());
			}
			if (reason == null && m.type().collection) {
				LoanAccounts.MessagingView v = accounts.messagingView(m.accountId());
				if (v.delinquencyStartedOn() == null) {
					reason = "연체가 해소되어 보내지 않음";
				} else if (!promises.findByAccountIdAndStatus(m.accountId(), PaymentPromise.Status.ACTIVE)
						.isEmpty()) {
					reason = "분할납부 약속 이행 중에는 자동 연체 문자를 보내지 않음";
				} else {
					ContactService.Verdict verdict = contacts.decide(m.accountId(), ContactQuotaGuard.Channel.SMS,
							today, time);
					if (verdict.result() != ContactLog.Result.ALLOWED) {
						reason = verdict.reason();
					}
					count = verdict.count();
				}
			}
			if (reason != null) {
				m.skip(reason);
				skipped++;
				continue;
			}
			MessagingPort.Sent result = messaging.send(m.customerId(), m.body());
			Long logId = m.type().collection ? contacts.recordSystem(m.accountId(), today,
					ContactQuotaGuard.Channel.SMS, count, "자동 " + m.type().name()).getId() : null;
			m.sent(now, result.providerId(), logId);
			sent++;
		}
		return new DispatchReport(sent, skipped);
	}

	List<OutboundMessage> of(long accountId) {
		return messages.findByAccountIdOrderByIdDesc(accountId);
	}
}
