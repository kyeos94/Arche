package dev.arche.servicing.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.servicing.LimitationInfo;
import dev.arche.servicing.engine.LimitationPeriod;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

interface LimitationEventRepository extends JpaRepository<LimitationEvent, Long> {

	List<LimitationEvent> findByAccountIdOrderByEventDate(long accountId);
}

/**
 * 소멸시효 계산 (G13). 기산일 이후의 입금(취소된 입금 제외, R29)·지급명령 신청·압류는 중단 사유, 판결 확정은 10년.
 */
@Service
class LimitationService {

	private final LimitationEventRepository limitationEvents;
	private final AccountEventRepository events;
	private final AcquisitionRepository acquisitions;
	private final BusinessDates businessDates;

	LimitationService(LimitationEventRepository limitationEvents, AccountEventRepository events,
			AcquisitionRepository acquisitions, BusinessDates businessDates) {
		this.limitationEvents = limitationEvents;
		this.events = events;
		this.acquisitions = acquisitions;
		this.businessDates = businessDates;
	}

	@Transactional(readOnly = true)
	LimitationInfo of(LoanAccount a) {
		LocalDate today = businessDates.today();
		AcquisitionRow acq = acquisitions.findByAccountId(a.getId()).orElse(null);
		LocalDate start;
		String reason;
		if (acq != null && acq.limitationStartDate() != null) {
			start = acq.limitationStartDate();
			reason = "원채권 기산일 승계";
		} else if (a.acceleratedOn() != null && acq == null) {
			start = a.acceleratedOn();
			reason = "기한이익상실";
		} else if (a.maturityDate() != null && a.maturityDate().isBefore(today)) {
			start = a.maturityDate();
			reason = "만기";
		} else {
			start = null;
			reason = null;
		}

		List<LimitationInfo.Event> all = new ArrayList<>();
		List<LocalDate> interruptions = new ArrayList<>();
		LocalDate judgment = acq == null ? null : acq.judgmentConfirmedOn();
		for (AccountEvent e : events.findByAccountIdOrderById(a.getId())) {
			if (e.active() && e.type() == AccountEvent.Type.PAYMENT) {
				if (e.limitationExcluded()) {
					// 보증인·제3자·법원 배당 입금은 채무자의 승인이 아니다 (R50)
					all.add(new LimitationInfo.Event(e.eventDate(), "PAYMENT", "제3자·법원 입금 (중단 미반영, R50)",
							false));
					continue;
				}
				boolean counted = start != null && e.eventDate().isAfter(start);
				all.add(new LimitationInfo.Event(e.eventDate(), "PAYMENT", "일부 변제(승인)", counted));
				if (counted) {
					interruptions.add(e.eventDate());
				}
			}
		}
		for (LimitationEvent e : limitationEvents.findByAccountIdOrderByEventDate(a.getId())) {
			if (e.voided()) {
				all.add(new LimitationInfo.Event(e.eventDate(), e.type().name(),
						"[무효] " + (e.memo() == null ? "" : e.memo() + " — ") + e.voidReason(), false));
				continue;
			}
			if (e.type() == LimitationEvent.Type.JUDGMENT_CONFIRMED) {
				judgment = judgment == null || e.eventDate().isAfter(judgment) ? e.eventDate() : judgment;
				all.add(new LimitationInfo.Event(e.eventDate(), e.type().name(), e.memo(), true));
			} else {
				boolean counted = start != null && e.eventDate().isAfter(start);
				all.add(new LimitationInfo.Event(e.eventDate(), e.type().name(), e.memo(), counted));
				if (counted) {
					interruptions.add(e.eventDate());
				}
			}
		}
		all.sort(Comparator.comparing(LimitationInfo.Event::date));
		LocalDate completion = LimitationPeriod.completionDate(start, interruptions, judgment);
		return new LimitationInfo(start, reason, all, completion,
				completion == null ? List.of() : LimitationPeriod.alertDates(completion),
				// 완성일이 끝나야 완성 (초일불산입, 정책서 5장)
				completion != null && completion.isBefore(today));
	}

	@Transactional
	long record(long accountId, LimitationEvent.Type type, LocalDate date, String memo) {
		return limitationEvents.save(new LimitationEvent(accountId, type, date, memo)).getId();
	}

	@Transactional
	void voidEvent(long accountId, long eventId, LocalDate on, String reason) {
		LimitationEvent e = limitationEvents.findById(eventId)
				.filter(x -> x.accountId() == accountId)
				.orElseThrow(() -> new dev.arche.common.BusinessException("LIMITATION_EVENT_NOT_FOUND",
						"소멸시효 중단 기록이 없습니다"));
		if (!e.voided()) {
			e.voidOn(on, reason);
		}
	}
}
