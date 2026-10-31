package dev.arche.collection.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.collection.ContactQuotaGuard;
import dev.arche.common.BusinessException;
import dev.arche.common.Money;
import dev.arche.servicing.AccountRef;
import dev.arche.staff.DataScope;
import dev.arche.staff.StaffDirectory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 추심 화면 API: 연락 기록(G14), 분할납부 약속(G19), 알림. 소멸시효(G13)는 /api/loans/{id}/limitation.
 */
@RestController
@RequestMapping("/api")
class CollectionController {

	record ContactRequest(@NotNull ContactQuotaGuard.Channel channel, String memo) {
	}

	record ContactRow(long id, LocalDate contactDate, OffsetDateTime contactedAt, String channel, String result,
			int countInWindow, String reason, String memo, String createdBy) {
	}

	record Contacts(LocalDate businessDate, int remainingToday, List<ContactRow> contacts) {
	}

	record PromiseRequest(@NotNull YearMonth firstMonth, int day, @Positive long amount, int months, String memo) {
	}

	record PromiseDto(long id, LocalDate registeredOn, YearMonth firstMonth, int day, long amount, int months,
			String status, LocalDate terminatedOn, boolean legalReview, String memo,
			List<PromiseService.JudgmentRow> judgments) {
	}

	record AlertDto(long id, long accountId, String type, String message, LocalDate alertDate, OffsetDateTime readAt) {
	}

	private final AccountAccess access;
	private final DeferralService deferralService;
	private final AutoMessageService autoMessages;
	private final ContactService contacts;
	private final ContactLogRepository contactLogs;
	private final PromiseService promises;
	private final CollectionAlertRepository alerts;
	private final StaffDirectory staff;
	private final BusinessDates businessDates;
	private final Clock clock;

	CollectionController(AccountAccess access, DeferralService deferralService, AutoMessageService autoMessages,
			ContactService contacts, ContactLogRepository contactLogs,
			PromiseService promises, CollectionAlertRepository alerts, StaffDirectory staff,
			BusinessDates businessDates, Clock clock) {
		this.access = access;
		this.deferralService = deferralService;
		this.autoMessages = autoMessages;
		this.contacts = contacts;
		this.contactLogs = contactLogs;
		this.promises = promises;
		this.alerts = alerts;
		this.staff = staff;
		this.businessDates = businessDates;
		this.clock = clock;
	}

	// ------------------------------------------------------------------ 연락 (G14)

	@GetMapping("/loans/{id}/contacts")
	@PreAuthorize("hasAuthority('loan.read')")
	@Transactional(readOnly = true)
	Contacts contacts(@PathVariable long id) {
		access.visible(id);
		LocalDate today = businessDates.today();
		return new Contacts(today, contacts.remaining(id, today),
				contactLogs.findByAccountIdOrderByIdDesc(id).stream().map(c -> new ContactRow(c.getId(),
						c.contactDate(), c.contactedAt(), c.channel().name(), c.result().name(), c.countInWindow(),
						c.blockReason(), c.memo(), c.createdBy())).toList());
	}

	@PostMapping("/loans/{id}/contacts")
	@PreAuthorize("hasAuthority('collection.activity')")
	ContactService.Decision recordContact(@PathVariable long id, @Valid @RequestBody ContactRequest body) {
		access.visible(id);
		return contacts.record(id, body.channel(), body.memo());
	}

	// ------------------------------------------------------------------ 분할납부 약속 (G19)

	@GetMapping("/loans/{id}/promises")
	@PreAuthorize("hasAuthority('loan.read')")
	@Transactional(readOnly = true)
	List<PromiseDto> promises(@PathVariable long id) {
		access.visible(id);
		return promises.of(id).stream().map(this::dto).toList();
	}

	@PostMapping("/loans/{id}/promises")
	@PreAuthorize("hasAuthority('collection.activity')")
	PromiseDto registerPromise(@PathVariable long id, @Valid @RequestBody PromiseRequest body) {
		AccountRef account = access.visible(id);
		return dto(promises.register(account, body.firstMonth(), body.day(), Money.won(body.amount()), body.months(),
				body.memo()));
	}

	private PromiseDto dto(PaymentPromise p) {
		return new PromiseDto(p.getId(), p.registeredOn(), p.firstMonth(), p.promiseDay(), p.amount().won(),
				p.months(), p.status().name(), p.terminatedOn(), p.legalReview(), p.memo(), promises.rows(p));
	}

	// ------------------------------------------------------------------ 연락 유예 (정책서 6장)

	record DeferralRequest(@NotNull LocalDate startDate, @NotNull LocalDate endDate, String reason) {
	}

	record ExtendRequest(@NotNull LocalDate endDate) {
	}

	record DeferralDto(long id, LocalDate startDate, LocalDate endDate, LocalDate originalEnd, boolean extended,
			String reason, String status, LocalDate endedOn, boolean activeToday) {
	}

	@GetMapping("/loans/{id}/deferrals")
	@PreAuthorize("hasAuthority('loan.read')")
	@Transactional(readOnly = true)
	List<DeferralDto> deferrals(@PathVariable long id) {
		access.visible(id);
		LocalDate today = businessDates.today();
		return deferralService.of(id).stream().map(d -> dto(d, today)).toList();
	}

	@PostMapping("/loans/{id}/deferrals")
	@PreAuthorize("hasAuthority('collection.activity')")
	DeferralDto registerDeferral(@PathVariable long id, @Valid @RequestBody DeferralRequest body) {
		access.visible(id);
		return dto(deferralService.register(id, body.startDate(), body.endDate(), body.reason()),
				businessDates.today());
	}

	@PostMapping("/loans/{id}/deferrals/{deferralId}/extend")
	@PreAuthorize("hasAuthority('collection.activity')")
	DeferralDto extendDeferral(@PathVariable long id, @PathVariable long deferralId,
			@Valid @RequestBody ExtendRequest body) {
		access.visible(id);
		return dto(deferralService.extend(id, deferralId, body.endDate()), businessDates.today());
	}

	@PostMapping("/loans/{id}/deferrals/{deferralId}/end")
	@PreAuthorize("hasAuthority('collection.activity')")
	DeferralDto endDeferral(@PathVariable long id, @PathVariable long deferralId) {
		access.visible(id);
		return dto(deferralService.end(id, deferralId), businessDates.today());
	}

	private static DeferralDto dto(ContactDeferral d, LocalDate today) {
		return new DeferralDto(d.getId(), d.startDate(), d.endDate(), d.originalEnd(), d.extended(), d.reason(),
				d.status().name(), d.endedOn(), d.coversDate(today));
	}

	// ------------------------------------------------------------------ 자동 문자 (정책서 6장)

	record MessageDto(long id, long accountId, String type, String body, LocalDate scheduledDate, String status,
			String skipReason, OffsetDateTime sentAt) {
		static MessageDto of(OutboundMessage m) {
			return new MessageDto(m.getId(), m.accountId(), m.type().name(), m.body(), m.scheduledDate(),
					m.status().name(), m.skipReason(), m.sentAt());
		}
	}

	@GetMapping("/loans/{id}/messages")
	@PreAuthorize("hasAuthority('loan.read')")
	@Transactional(readOnly = true)
	List<MessageDto> messages(@PathVariable long id) {
		access.visible(id);
		return autoMessages.of(id).stream().map(MessageDto::of).toList();
	}

	/** 수동 발송 (배치 관리). 평소에는 연락 가능 시간에 5분마다 자동 발송 */
	@PostMapping("/collection/messages/dispatch")
	@PreAuthorize("hasAuthority('admin.manage')")
	AutoMessageService.DispatchReport dispatch() {
		return autoMessages.dispatch();
	}

	// ------------------------------------------------------------------ 알림

	@GetMapping("/collection/alerts")
	@PreAuthorize("hasAuthority('loan.read')")
	@Transactional(readOnly = true)
	List<AlertDto> alerts(@RequestParam(defaultValue = "false") boolean unreadOnly) {
		DataScope scope = staff.loanScope();
		return alerts.findTop200ByOrderByIdDesc().stream().filter(a -> scope.allows(a.staffId()))
				.filter(a -> !unreadOnly || a.readAt() == null).map(a -> new AlertDto(a.getId(), a.accountId(),
						a.type().name(), a.message(), a.alertDate(), a.readAt()))
				.toList();
	}

	@PostMapping("/collection/alerts/{id}/read")
	@PreAuthorize("hasAuthority('loan.read')")
	@Transactional
	AlertDto read(@PathVariable long id) {
		CollectionAlert a = alerts.findById(id)
				.orElseThrow(() -> new BusinessException("ALERT_NOT_FOUND", "알림이 없습니다"));
		if (!staff.loanScope().allows(a.staffId())) {
			throw new BusinessException("FORBIDDEN", "담당 범위 밖의 알림입니다");
		}
		a.read(OffsetDateTime.now(clock));
		return new AlertDto(a.getId(), a.accountId(), a.type().name(), a.message(), a.alertDate(), a.readAt());
	}
}
