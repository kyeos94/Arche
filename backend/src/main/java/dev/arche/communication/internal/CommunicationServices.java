package dev.arche.communication.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.collection.CollectionContacts;
import dev.arche.collection.ContactQuotaGuard;
import dev.arche.common.BusinessException;
import dev.arche.customer.Customers;
import dev.arche.integration.FaxPort;
import dev.arche.integration.MessagingPort;
import dev.arche.integration.PostalPort;
import dev.arche.servicing.AccountRef;
import dev.arche.servicing.LoanAccounts;
import dev.arche.staff.CurrentStaff;
import dev.arche.staff.StaffDirectory;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

interface MessageTemplateRepository extends JpaRepository<MessageTemplate, Long> {

	List<MessageTemplate> findAllByOrderByChannelAscCodeAsc();

	boolean existsByCode(String code);
}

interface CommunicationDispatchRepository extends JpaRepository<CommunicationDispatch, Long> {

	List<CommunicationDispatch> findByAccountIdOrderByIdDesc(long accountId);

	List<CommunicationDispatch> findByStatusAndContactDateLessThanEqualOrderById(CommunicationDispatch.Status status,
			LocalDate date);

	List<CommunicationDispatch> findByChannelAndStatusInOrderById(DispatchChannel channel,
			List<CommunicationDispatch.Status> statuses);
}

interface ContactMemoRepository extends JpaRepository<ContactMemo, Long> {

	List<ContactMemo> findByAccountIdOrderByIdDesc(long accountId);
}

/** 계약 조회 범위 검사 (정책서 9.1 담당 건 / 팀 전체) */
@Service
class CommunicationAccess {

	private final LoanAccounts accounts;
	private final StaffDirectory staff;

	CommunicationAccess(LoanAccounts accounts, StaffDirectory staff) {
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

/** 템플릿 관리 (운영 관리 권한) */
@Service
class TemplateService {

	private final MessageTemplateRepository templates;

	TemplateService(MessageTemplateRepository templates) {
		this.templates = templates;
	}

	List<MessageTemplate> all() {
		return templates.findAllByOrderByChannelAscCodeAsc();
	}

	MessageTemplate get(long id) {
		return templates.findById(id)
				.orElseThrow(() -> new BusinessException("TEMPLATE_NOT_FOUND", "템플릿이 없습니다"));
	}

	@Transactional
	MessageTemplate create(String code, String name, DispatchChannel channel, DispatchPurpose purpose, String title,
			String body) {
		if (templates.existsByCode(code)) {
			throw new BusinessException("DUPLICATE", "같은 코드의 템플릿이 있습니다");
		}
		check(channel, title, body);
		return templates.save(new MessageTemplate(code, name, channel, purpose, title, body));
	}

	@Transactional
	MessageTemplate update(long id, String name, DispatchChannel channel, DispatchPurpose purpose, String title,
			String body, boolean active) {
		check(channel, title, body);
		MessageTemplate t = get(id);
		t.update(name, channel, purpose, title, body, active);
		return t;
	}

	private static void check(DispatchChannel channel, String title, String body) {
		TemplateRenderer.validate(body);
		if (channel == DispatchChannel.SMS && body.length() > CommunicationService.SMS_MAX) {
			throw new BusinessException("TEMPLATE_INVALID", "문자는 " + CommunicationService.SMS_MAX + "자까지입니다");
		}
		if (channel != DispatchChannel.SMS && (title == null || title.isBlank())) {
			throw new BusinessException("TEMPLATE_INVALID", "팩스·우편은 제목이 필요합니다");
		}
	}
}

/**
 * 수동 발송 (R59): 목적이 추심 안내면 연락 판정(소멸시효 → 연락 유예 → 연락 가능 시간 → 7일 7회)을 통과해야 보내고,
 * 법정·계약상 통지면 제한 없이 보낸다. 판정과 기록은 collection 모듈이 한다.
 */
@Service
class CommunicationService {

	private static final Logger log = LoggerFactory.getLogger(CommunicationService.class);
	static final int SMS_MAX = 500;
	static final LocalTime OPEN = LocalTime.of(8, 0);
	static final LocalTime CLOSE = LocalTime.of(21, 0);

	record SendCommand(DispatchChannel channel, @Nullable Long templateId, @Nullable String title,
			@Nullable String body, @Nullable String faxNumber) {

		@Override
		public String toString() {
			return "SendCommand[" + channel + ", template " + templateId + "]";
		}
	}

	private final CommunicationDispatchRepository dispatches;
	private final ContactMemoRepository memos;
	private final TemplateService templates;
	private final CollectionContacts contacts;
	private final LoanAccounts accounts;
	private final Customers customers;
	private final MessagingPort messaging;
	private final FaxPort fax;
	private final PostalPort postal;
	private final StaffDirectory staff;
	private final BusinessDates businessDates;
	private final Clock clock;
	private final TransactionTemplate tx;

	CommunicationService(CommunicationDispatchRepository dispatches, ContactMemoRepository memos,
			TemplateService templates, CollectionContacts contacts, LoanAccounts accounts, Customers customers,
			MessagingPort messaging, FaxPort fax, PostalPort postal, StaffDirectory staff, BusinessDates businessDates,
			Clock clock, PlatformTransactionManager transactionManager) {
		this.tx = new TransactionTemplate(transactionManager);
		this.dispatches = dispatches;
		this.memos = memos;
		this.templates = templates;
		this.contacts = contacts;
		this.accounts = accounts;
		this.customers = customers;
		this.messaging = messaging;
		this.fax = fax;
		this.postal = postal;
		this.staff = staff;
		this.businessDates = businessDates;
		this.clock = clock;
	}

	/** 템플릿을 계약 값으로 채운다 (미리보기·발송 공용) */
	@Transactional(readOnly = true)
	String render(AccountRef account, String body) {
		LoanAccounts.MessagingView v = accounts.messagingView(account.id());
		LocalDate today = businessDates.today();
		Integer days = v.delinquencyStartedOn() == null ? null
				: (int) ChronoUnit.DAYS.between(v.delinquencyStartedOn(), today) + 1;
		Map<String, String> values = TemplateRenderer.values(customers.get(v.customerId()).name(), v.accountNo(), days,
				won(v.payoffAmount()), won(account.principalBalance()),
				v.nextDueDate() == null ? null : v.nextDueDate().toString(), today.toString());
		return TemplateRenderer.render(body, values);
	}

	@Transactional
	CommunicationDispatch send(AccountRef account, SendCommand c) {
		MessageTemplate t = c.templateId() == null ? null : templates.get(c.templateId());
		if (t != null && (!t.active() || t.channel() != c.channel())) {
			throw new BusinessException("TEMPLATE_INVALID", "사용할 수 없는 템플릿입니다 (중지되었거나 발송 수단이 다름)");
		}
		// 자유 입력은 항상 추심 안내로 본다: 목적을 직접 골라 한도를 피하지 못하게 (R59)
		DispatchPurpose purpose = t == null ? DispatchPurpose.COLLECTION : t.purpose();
		String rawBody = t != null ? t.body() : c.body();
		if (rawBody == null || rawBody.isBlank()) {
			throw new BusinessException("BODY_REQUIRED", "내용을 입력하거나 템플릿을 고르세요");
		}
		TemplateRenderer.validate(rawBody);
		String title = t != null && t.title() != null ? t.title() : c.title();
		if (c.channel() != DispatchChannel.SMS && (title == null || title.isBlank())) {
			throw new BusinessException("TITLE_REQUIRED", "팩스·우편은 제목이 필요합니다");
		}
		if (c.channel() == DispatchChannel.FAX && (c.faxNumber() == null
				|| c.faxNumber().replaceAll("[^0-9]", "").length() < 8)) {
			throw new BusinessException("FAX_NUMBER_REQUIRED", "받는 팩스 번호를 입력하세요");
		}
		if (purpose == DispatchPurpose.COLLECTION && account.paidOffOn() != null) {
			throw new BusinessException("ACCOUNT_CLOSED", "완제된 계약에는 추심 안내를 보낼 수 없습니다");
		}
		String body = render(account, rawBody);
		if (c.channel() == DispatchChannel.SMS && body.length() > SMS_MAX) {
			throw new BusinessException("BODY_TOO_LONG", "문자는 " + SMS_MAX + "자까지입니다");
		}
		LoanAccounts.MessagingView v = accounts.messagingView(account.id());
		CurrentStaff me = staff.current();
		CommunicationDispatch d = dispatches.save(new CommunicationDispatch(account.id(), v.customerId(), c.channel(),
				purpose, t == null ? null : t.getId(), title, body,
				c.channel() == DispatchChannel.FAX ? c.faxNumber() : null, businessDates.today(), me.id()));

		CollectionContacts.Verdict verdict = contacts.contact(account.id(), quotaChannel(c.channel()),
				purpose == DispatchPurpose.NOTICE, label(d));
		CommunicationDispatch.Status judged = switch (verdict.result()) {
			case "BLOCKED" -> CommunicationDispatch.Status.BLOCKED;
			case "SCHEDULED" -> CommunicationDispatch.Status.SCHEDULED;
			default -> CommunicationDispatch.Status.SENT;
		};
		d.judged(judged, verdict.contactDate(), verdict.countInWindow(), verdict.reason(), verdict.contactLogId());
		if (judged == CommunicationDispatch.Status.SENT) {
			deliver(d);
		}
		return d;
	}

	/** 실제 기관 호출 */
	private void deliver(CommunicationDispatch d) {
		OffsetDateTime now = OffsetDateTime.now(clock);
		switch (d.channel()) {
			case SMS -> {
				MessagingPort.Sent sent = messaging.send(d.customerId(), d.body());
				d.sent(CommunicationDispatch.Status.SENT, sent.providerId(), null, now, null);
			}
			case FAX -> {
				FaxPort.FaxResult r = fax.send(new FaxPort.FaxRequest(d.recipient(), d.title(), d.body()));
				d.sent(r.accepted() ? CommunicationDispatch.Status.SENT : CommunicationDispatch.Status.FAILED,
						r.providerId(), r.fileRef(), now, r.accepted() ? null : "팩스 송신 실패 (" + r.resultCode() + ")");
			}
			case POST -> {
				PostalPort.Accepted a = postal.send(new PostalPort.PostalRequest(d.customerId(), d.title(), d.body()));
				d.sent(CommunicationDispatch.Status.ACCEPTED, a.trackingNo(), a.fileRef(), now, null);
			}
		}
	}

	/** 예약 문자 발송: 연락 가능 시간 안에서 5분마다. 보내기 직전에 소멸시효·연락 유예를 다시 본다 (R59) */
	@Scheduled(fixedDelayString = "${arche.messaging.dispatch-delay:PT5M}")
	@SchedulerLock(name = "communication-dispatch", lockAtMostFor = "PT10M")
	void scheduledDispatch() {
		try {
			tx.executeWithoutResult(s -> dispatchScheduled());
		} catch (RuntimeException e) {
			log.error("예약 발송 실패", e);
		}
	}

	@Transactional
	int dispatchScheduled() {
		LocalTime time = OffsetDateTime.now(clock).toLocalTime();
		if (time.isBefore(OPEN) || !time.isBefore(CLOSE)) {
			return 0;
		}
		LocalDate today = businessDates.today();
		int sent = 0;
		for (CommunicationDispatch d : dispatches
				.findByStatusAndContactDateLessThanEqualOrderById(CommunicationDispatch.Status.SCHEDULED, today)) {
			Optional<String> blocked = d.purpose() == DispatchPurpose.COLLECTION
					? contacts.blockingReason(d.accountId(), today)
					: Optional.empty();
			if (blocked.isPresent()) {
				d.block(blocked.get());
				continue;
			}
			deliver(d);
			sent++;
		}
		return sent;
	}

	/** 등기 배달 조회 (1시간마다 + 화면 수동). 반송이면 담당자가 다시 보낸다 */
	@Scheduled(fixedDelayString = "${arche.postal.track-delay:PT1H}")
	@SchedulerLock(name = "postal-tracking", lockAtMostFor = "PT30M")
	void scheduledTracking() {
		try {
			tx.executeWithoutResult(s -> trackAll());
		} catch (RuntimeException e) {
			log.error("배달 조회 실패", e);
		}
	}

	@Transactional
	int trackAll() {
		int changed = 0;
		for (CommunicationDispatch d : dispatches.findByChannelAndStatusInOrderById(DispatchChannel.POST,
				List.of(CommunicationDispatch.Status.ACCEPTED, CommunicationDispatch.Status.IN_TRANSIT))) {
			changed += track(d) ? 1 : 0;
		}
		return changed;
	}

	@Transactional
	boolean track(CommunicationDispatch d) {
		if (d.channel() != DispatchChannel.POST || d.providerId() == null) {
			throw new BusinessException("NOT_POSTAL", "등기우편만 배달 조회를 합니다");
		}
		PostalPort.Tracking t = postal.track(d.providerId(), d.contactDate(), businessDates.today());
		CommunicationDispatch.Status next = CommunicationDispatch.Status.valueOf(t.status().name());
		if (next == d.status()) {
			return false;
		}
		d.delivery(next, t.deliveredOn());
		return true;
	}

	@Transactional
	ContactMemo memo(long accountId, String body) {
		if (body == null || body.isBlank()) {
			throw new BusinessException("BODY_REQUIRED", "메모를 입력하세요");
		}
		CurrentStaff me = staff.current();
		return memos.save(new ContactMemo(accountId, businessDates.today(), body.strip(), me.id(),
				OffsetDateTime.now(clock), me.loginId()));
	}

	CommunicationDispatch get(long accountId, long dispatchId) {
		return dispatches.findById(dispatchId).filter(d -> d.accountId() == accountId)
				.orElseThrow(() -> new BusinessException("DISPATCH_NOT_FOUND", "발송 이력이 없습니다"));
	}

	static ContactQuotaGuard.Channel quotaChannel(DispatchChannel c) {
		return switch (c) {
			case SMS -> ContactQuotaGuard.Channel.SMS;
			case FAX -> ContactQuotaGuard.Channel.FAX;
			case POST -> ContactQuotaGuard.Channel.POST;
		};
	}

	private static String label(CommunicationDispatch d) {
		String head = switch (d.channel()) {
			case SMS -> "문자";
			case FAX -> "팩스";
			case POST -> "우편";
		};
		String text = d.title() != null ? d.title() : d.body();
		return head + ": " + (text.length() > 40 ? text.substring(0, 40) + "…" : text);
	}

	static String won(long amount) {
		return String.format("%,d원", amount);
	}
}
