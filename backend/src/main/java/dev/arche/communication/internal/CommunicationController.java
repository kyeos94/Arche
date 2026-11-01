package dev.arche.communication.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.collection.CollectionContacts;
import dev.arche.common.Masking;
import dev.arche.servicing.AccountRef;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * 연락 타임라인·빠른 입력(문자·팩스·우편 발송, 메모)과 템플릿 관리 API (guide 13.2 우측 패널, 13.3 문자 템플릿).
 * 전화·방문 기록은 기존 /api/loans/{id}/contacts를 쓴다.
 */
@RestController
@RequestMapping("/api")
class CommunicationController {

	// ------------------------------------------------------------------ DTO

	record TimelineResponse(LocalDate businessDate, int remainingToday, String blockingReason,
			List<Timeline.Item> content, int page, int size, long totalElements, boolean hasNext) {
	}

	record SendRequest(@NotNull DispatchChannel channel, Long templateId, @Size(max = 100) String title,
			@Size(max = 2000) String body, @Size(max = 20) String faxNumber) {

		@Override
		public String toString() {
			return "SendRequest[" + channel + ", template " + templateId + "]";
		}
	}

	record DispatchDto(long id, String channel, String purpose, String title, String body, String recipientMasked,
			String status, String reason, LocalDate contactDate, int countInWindow, String providerId,
			String fileRef, OffsetDateTime sentAt, LocalDate deliveredOn, int remainingToday) {
	}

	record MemoRequest(@NotBlank @Size(max = 1000) String body) {
	}

	/** 문자 일괄 발송 (ADR 0011). 템플릿 또는 직접 입력 중 하나 */
	record BulkSmsRequest(@NotNull @Size(min = 1, max = BULK_MAX) List<Long> accountIds, Long templateId,
			@Size(max = 2000) String body) {

		@Override
		public String toString() {
			return "BulkSmsRequest[" + accountIds.size() + "건, template " + templateId + "]";
		}
	}

	/** 계약별 결과: SENT(발송), SCHEDULED(야간이라 예약), BLOCKED(한도·유예 등으로 차단, 기록은 남음), SKIPPED(보내지 않음) */
	record BulkSmsItem(long accountId, String accountNo, String result, String reason) {
	}

	record BulkSmsResult(int sent, int scheduled, int blocked, int skipped, List<BulkSmsItem> items) {
	}

	static final int BULK_MAX = 200;

	record TemplateRequest(@NotBlank @Pattern(regexp = "[A-Z0-9_]{2,30}") String code, @NotBlank @Size(max = 60) String name,
			@NotNull DispatchChannel channel, @NotNull DispatchPurpose purpose, @Size(max = 100) String title,
			@NotBlank @Size(max = 2000) String body, Boolean active) {
	}

	record TemplateDto(long id, String code, String name, String channel, String purpose, String title, String body,
			boolean active, List<String> placeholders) {
		static TemplateDto of(MessageTemplate t) {
			return new TemplateDto(t.getId(), t.code(), t.name(), t.channel().name(), t.purpose().name(), t.title(),
					t.body(), t.active(), TemplateRenderer.PLACEHOLDERS);
		}
	}

	record PreviewDto(String title, String body, String purpose) {
	}

	private final CommunicationAccess access;
	private final CommunicationService service;
	private final TemplateService templates;
	private final CommunicationDispatchRepository dispatches;
	private final ContactMemoRepository memos;
	private final CollectionContacts contacts;
	private final BusinessDates businessDates;
	private final Clock clock;

	CommunicationController(CommunicationAccess access, CommunicationService service, TemplateService templates,
			CommunicationDispatchRepository dispatches, ContactMemoRepository memos, CollectionContacts contacts,
			BusinessDates businessDates, Clock clock) {
		this.access = access;
		this.service = service;
		this.templates = templates;
		this.dispatches = dispatches;
		this.memos = memos;
		this.contacts = contacts;
		this.businessDates = businessDates;
		this.clock = clock;
	}

	// ------------------------------------------------------------------ 타임라인

	@GetMapping("/loans/{id}/timeline")
	@PreAuthorize("hasAuthority('loan.read')")
	@Transactional(readOnly = true)
	TimelineResponse timeline(@PathVariable long id, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		access.visible(id);
		LocalDate today = businessDates.today();
		Set<Long> duplicated = new HashSet<>();
		List<CommunicationDispatch> ds = dispatches.findByAccountIdOrderByIdDesc(id);
		ds.stream().map(CommunicationDispatch::contactLogId).filter(java.util.Objects::nonNull).forEach(duplicated::add);
		List<CollectionContacts.AutoMessageEntry> autos = contacts.autoMessages(id);
		autos.stream().map(CollectionContacts.AutoMessageEntry::contactLogId).filter(java.util.Objects::nonNull)
				.forEach(duplicated::add);

		List<Timeline.Item> contactItems = contacts.contactLog(id).stream()
				.map(c -> new Timeline.Item(Timeline.Kind.CONTACT, c.id(), c.contactedAt(), c.contactDate(),
						c.channel(), null, c.memo(), c.result(), c.reason(), c.createdBy()))
				.toList();
		List<Timeline.Item> autoItems = autos.stream()
				.map(m -> new Timeline.Item(Timeline.Kind.AUTO_MESSAGE, m.id(),
						m.sentAt() != null ? m.sentAt() : at(m.scheduledDate(), LocalTime.of(8, 0)), m.scheduledDate(),
						"SMS", m.type(), m.body(), m.status(), m.skipReason(), "system"))
				.toList();
		List<Timeline.Item> dispatchItems = ds.stream()
				.map(d -> new Timeline.Item(Timeline.Kind.DISPATCH, d.getId(),
						d.sentAt() != null ? d.sentAt() : d.getCreatedAt(), d.contactDate(), d.channel().name(),
						d.title(), d.body(), d.status().name(), d.blockReason(), d.getCreatedBy()))
				.toList();
		List<Timeline.Item> memoItems = memos.findByAccountIdOrderByIdDesc(id).stream()
				.map(m -> new Timeline.Item(Timeline.Kind.MEMO, m.getId(), m.createdAt(), m.memoDate(), "MEMO", null,
						m.body(), null, null, m.createdBy()))
				.toList();
		Timeline.Page p = Timeline.merge(contactItems, autoItems, dispatchItems, memoItems, duplicated,
				Math.max(0, page), Math.min(Math.max(1, size), 100));
		return new TimelineResponse(today, contacts.remaining(id, today),
				contacts.blockingReason(id, today).orElse(null), p.content(), p.page(), p.size(), p.totalElements(),
				p.hasNext());
	}

	private OffsetDateTime at(LocalDate date, LocalTime time) {
		return date.atTime(time).atZone(clock.getZone()).toOffsetDateTime();
	}

	// ------------------------------------------------------------------ 발송·메모

	@PostMapping("/loans/{id}/dispatches")
	@PreAuthorize("hasAuthority('collection.activity')")
	DispatchDto send(@PathVariable long id, @Valid @RequestBody SendRequest body) {
		AccountRef account = access.visible(id);
		CommunicationDispatch d = service.send(account, new CommunicationService.SendCommand(body.channel(),
				body.templateId(), body.title(), body.body(), body.faxNumber()));
		return dto(d);
	}

	/**
	 * 문자 일괄 발송 (ADR 0011): 계약마다 상세 화면의 문자 발송과 같은 규칙(7일 7회·연락 유예·추심 중지·야간 예약·완제 계약 제외)을
	 * 따로 적용한다. 계약마다 별도 트랜잭션이라 한 건이 막혀도 나머지는 보낸다. 담당 범위 밖 계약은 건너뛴다
	 */
	@PostMapping("/dispatches/bulk-sms")
	@PreAuthorize("hasAuthority('collection.activity')")
	BulkSmsResult bulkSms(@Valid @RequestBody BulkSmsRequest body) {
		List<BulkSmsItem> items = new java.util.ArrayList<>();
		int sent = 0;
		int scheduled = 0;
		int blocked = 0;
		int skipped = 0;
		for (Long id : new java.util.LinkedHashSet<>(body.accountIds())) {
			String accountNo = null;
			try {
				AccountRef account = access.visible(id);
				accountNo = account.accountNo();
				CommunicationDispatch d = service.send(account, new CommunicationService.SendCommand(DispatchChannel.SMS,
						body.templateId(), null, body.body(), null));
				String result = d.status().name();
				switch (d.status()) {
					case SENT -> sent++;
					case SCHEDULED -> scheduled++;
					// 기관 처리 실패(FAILED)도 받지 못했으므로 차단과 같이 센다
					default -> blocked++;
				}
				items.add(new BulkSmsItem(id, accountNo, result, d.blockReason()));
			} catch (dev.arche.common.BusinessException e) {
				skipped++;
				items.add(new BulkSmsItem(id, accountNo, "SKIPPED", e.getMessage()));
			}
		}
		return new BulkSmsResult(sent, scheduled, blocked, skipped, items);
	}

	@GetMapping("/loans/{id}/dispatches")
	@PreAuthorize("hasAuthority('loan.read')")
	@Transactional(readOnly = true)
	List<DispatchDto> dispatches(@PathVariable long id) {
		access.visible(id);
		return dispatches.findByAccountIdOrderByIdDesc(id).stream().map(this::dto).toList();
	}

	@PostMapping("/loans/{id}/dispatches/{dispatchId}/track")
	@PreAuthorize("hasAuthority('collection.activity')")
	@Transactional
	DispatchDto track(@PathVariable long id, @PathVariable long dispatchId) {
		access.visible(id);
		CommunicationDispatch d = service.get(id, dispatchId);
		service.track(d);
		return dto(d);
	}

	@PostMapping("/loans/{id}/memos")
	@PreAuthorize("hasAuthority('collection.activity')")
	Timeline.Item memo(@PathVariable long id, @Valid @RequestBody MemoRequest body) {
		access.visible(id);
		ContactMemo m = service.memo(id, body.body());
		return new Timeline.Item(Timeline.Kind.MEMO, m.getId(), m.createdAt(), m.memoDate(), "MEMO", null, m.body(),
				null, null, m.createdBy());
	}

	@GetMapping("/loans/{id}/templates/{templateId}/preview")
	@PreAuthorize("hasAuthority('loan.read')")
	@Transactional(readOnly = true)
	PreviewDto preview(@PathVariable long id, @PathVariable long templateId) {
		AccountRef account = access.visible(id);
		MessageTemplate t = templates.get(templateId);
		return new PreviewDto(t.title(), service.render(account, t.body()), t.purpose().name());
	}

	private DispatchDto dto(CommunicationDispatch d) {
		return new DispatchDto(d.getId(), d.channel().name(), d.purpose().name(), d.title(), d.body(),
				d.recipient() == null ? null : Masking.phone(d.recipient()), d.status().name(), d.blockReason(),
				d.contactDate(), d.countInWindow(), d.providerId(), d.fileRef(), d.sentAt(), d.deliveredOn(),
				contacts.remaining(d.accountId(), businessDates.today()));
	}

	// ------------------------------------------------------------------ 템플릿 관리

	@GetMapping("/message-templates")
	@PreAuthorize("hasAuthority('loan.read') or hasAuthority('admin.manage')")
	@Transactional(readOnly = true)
	List<TemplateDto> templates(@RequestParam(defaultValue = "false") boolean activeOnly) {
		return templates.all().stream().filter(t -> !activeOnly || t.active()).map(TemplateDto::of).toList();
	}

	@PostMapping("/message-templates")
	@PreAuthorize("hasAuthority('admin.manage')")
	TemplateDto createTemplate(@Valid @RequestBody TemplateRequest body) {
		return TemplateDto.of(templates.create(body.code(), body.name(), body.channel(), body.purpose(), body.title(),
				body.body()));
	}

	@PostMapping("/message-templates/{templateId}")
	@PreAuthorize("hasAuthority('admin.manage')")
	TemplateDto updateTemplate(@PathVariable long templateId, @Valid @RequestBody TemplateRequest body) {
		return TemplateDto.of(templates.update(templateId, body.name(), body.channel(), body.purpose(), body.title(),
				body.body(), body.active() == null || body.active()));
	}
}
