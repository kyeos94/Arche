package dev.arche.collection.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.collection.CollectionContacts;
import dev.arche.collection.ContactQuotaGuard;
import dev.arche.servicing.LimitationInfo;
import dev.arche.servicing.LoanAccounts;
import dev.arche.staff.CurrentStaff;
import dev.arche.staff.StaffDirectory;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link CollectionContacts} 구현. 판정은 {@link ContactService#decide}를 그대로 쓴다 */
@Service
class CollectionContactsService implements CollectionContacts {

	private final ContactService contacts;
	private final ContactLogRepository logs;
	private final DeferralService deferrals;
	private final AutoMessageService autoMessages;
	private final LoanAccounts accounts;
	private final StaffDirectory staff;
	private final BusinessDates businessDates;
	private final Clock clock;
	private final CollectionStopService stops;

	CollectionContactsService(ContactService contacts, ContactLogRepository logs, DeferralService deferrals,
			AutoMessageService autoMessages, LoanAccounts accounts, StaffDirectory staff, BusinessDates businessDates,
			Clock clock, CollectionStopService stops) {
		this.stops = stops;
		this.contacts = contacts;
		this.logs = logs;
		this.deferrals = deferrals;
		this.autoMessages = autoMessages;
		this.accounts = accounts;
		this.staff = staff;
		this.businessDates = businessDates;
		this.clock = clock;
	}

	@Override
	@Transactional
	public Verdict contact(long accountId, ContactQuotaGuard.Channel channel, boolean statutory,
			@Nullable String memo) {
		OffsetDateTime now = OffsetDateTime.now(clock);
		ContactQuotaGuard.Channel recorded = statutory ? ContactQuotaGuard.Channel.STATUTORY_NOTICE : channel;
		// 우편은 접수 시각이 연락 시각이 아니므로 연락 가능 시간 제한 없음 (R59)
		LocalTime time = channel == ContactQuotaGuard.Channel.POST ? ContactService.OPEN : now.toLocalTime();
		ContactService.Verdict v = contacts.decide(accountId, recorded, businessDates.today(), time);
		CurrentStaff me = staff.currentIfAny().orElse(null);
		ContactLog log = logs.save(new ContactLog(accountId, v.contactDate(), now, recorded, v.result(), v.count(),
				v.reason(), memo, me == null ? null : me.id(), me == null ? "system" : me.loginId()));
		return new Verdict(v.contactDate(), v.result().name(), v.count(), v.reason(), log.getId());
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<String> blockingReason(long accountId, LocalDate date) {
		LimitationInfo limitation = accounts.limitation(accountId);
		if (limitation.completed()) {
			return Optional.of("소멸시효가 완성된 채권(" + limitation.completionDate() + ")은 추심 연락을 할 수 없습니다");
		}
		return deferrals.activeOn(accountId, date)
				.map(d -> "연락 유예 중입니다 (" + d.startDate() + " ~ " + d.endDate() + ", " + d.reason() + ")");
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<String> collectionStopReason(long accountId) {
		return Optional.ofNullable(stops.blockReason(accountId));
	}

	@Override
	@Transactional(readOnly = true)
	public int remaining(long accountId, LocalDate date) {
		return contacts.remaining(accountId, date);
	}

	@Override
	@Transactional(readOnly = true)
	public List<ContactEntry> contactLog(long accountId) {
		return logs.findByAccountIdOrderByIdDesc(accountId).stream()
				.map(c -> new ContactEntry(c.getId(), c.contactDate(), c.contactedAt(), c.channel().name(),
						c.result().name(), c.blockReason(), c.memo(), c.createdBy()))
				.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public List<AutoMessageEntry> autoMessages(long accountId) {
		return autoMessages.of(accountId).stream().map(m -> new AutoMessageEntry(m.getId(), m.type().name(), m.body(),
				m.scheduledDate(), m.status().name(), m.skipReason(), m.sentAt(), m.contactLogId())).toList();
	}
}
