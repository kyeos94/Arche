package dev.arche.collection.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.common.BusinessException;
import dev.arche.common.InterestRate;
import dev.arche.common.jpa.NumberGenerator;
import dev.arche.servicing.AccountRef;
import dev.arche.servicing.LoanAccounts;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

interface CollectionStopRepository extends JpaRepository<CollectionStop, Long> {

	List<CollectionStop> findByAccountIdOrderByIdDesc(long accountId);

	List<CollectionStop> findByAccountIdAndStatus(long accountId, CollectionStop.Status status);

	List<CollectionStop> findByGroupNoAndStatus(String groupNo, CollectionStop.Status status);
}

/**
 * 추심 중지 (정책서 R45·R46). 등록하면 그 고객의 완제·매각되지 않은 모든 계약에 중지를 걸고, 개인회생·파산이면 계약마다
 * 사유 발생일부터 이자 부과 중단을 servicing에 요청한다. 해제는 묶음(groupNo) 단위.
 */
@Service
class CollectionStopService {

	private final CollectionStopRepository stops;
	private final LoanAccounts accounts;
	private final BusinessDates businessDates;
	private final NumberGenerator numbers;

	CollectionStopService(CollectionStopRepository stops, LoanAccounts accounts, BusinessDates businessDates,
			NumberGenerator numbers) {
		this.stops = stops;
		this.accounts = accounts;
		this.businessDates = businessDates;
		this.numbers = numbers;
	}

	Optional<CollectionStop> active(long accountId) {
		return stops.findByAccountIdAndStatus(accountId, CollectionStop.Status.ACTIVE).stream().findFirst();
	}

	/** 연락·자동 문자 차단 사유 (R40 매각 채권, R45 추심 중지). 없으면 null */
	@Nullable
	String blockReason(long accountId) {
		AccountRef a = accounts.get(accountId);
		if ("SOLD".equals(a.status())) {
			return "매각된 채권은 추심 연락을 하지 않습니다 (R40)";
		}
		return active(accountId).map(s -> "추심 중지 중입니다 (" + s.reason().label + ", " + s.effectiveDate() + ") (R45)")
				.orElse(null);
	}

	List<CollectionStop> of(long accountId) {
		return stops.findByAccountIdOrderByIdDesc(accountId);
	}

	@Transactional
	List<CollectionStop> register(long accountId, CollectionStop.Reason reason, LocalDate effectiveDate, String memo) {
		if (memo == null || memo.isBlank()) {
			throw new BusinessException("REASON_REQUIRED", "근거(통지 수령 내용·사건 정보 등)를 입력하세요");
		}
		LocalDate today = businessDates.today();
		if (effectiveDate == null || effectiveDate.isAfter(today)) {
			throw new BusinessException("STOP_INVALID", "사유 발생일은 업무일자 이후일 수 없습니다 (R45)");
		}
		if (active(accountId).isPresent()) {
			throw new BusinessException("STOP_EXISTS", "이미 추심 중지 중입니다. 해제 후 다시 등록하세요");
		}
		List<AccountRef> targets = accounts.sameCustomerOpenAccounts(accountId);
		if (targets.isEmpty()) {
			throw new BusinessException("ACCOUNT_CLOSED", "완제·매각되지 않은 계약이 없습니다");
		}
		long customerId = accounts.messagingView(accountId).customerId();
		String group = numbers.next("collection_stop_group_seq", "CS");
		String label = reason.label + " " + effectiveDate;
		return targets.stream().filter(t -> active(t.id()).isEmpty()).map(t -> {
			CollectionStop s = new CollectionStop(group, t.id(), customerId, reason, effectiveDate, memo.trim());
			if (reason.suspendsInterest) {
				s.interestSuspended(accounts.suspendInterest(t.id(), effectiveDate, "추심 중지 " + group + " " + label));
			}
			return stops.save(s);
		}).toList();
	}

	/** 묶음 전체 해제. 이자 부과 중단은 업무일자로 끝나 그날부터 다시 이자 (중단 기간 이자는 되살리지 않음, R46) */
	@Transactional
	List<CollectionStop> release(long accountId, long stopId, String why) {
		if (why == null || why.isBlank()) {
			throw new BusinessException("REASON_REQUIRED", "해제 사유(기각·폐지·상속인 확정 등)를 입력하세요");
		}
		CollectionStop s = find(accountId, stopId);
		if (!s.active()) {
			throw new BusinessException("STOP_INVALID", "이미 해제된 추심 중지입니다");
		}
		LocalDate today = businessDates.today();
		List<CollectionStop> group = stops.findByGroupNoAndStatus(s.groupNo(), CollectionStop.Status.ACTIVE);
		for (CollectionStop g : group) {
			if (g.interestEventId() != null) {
				AccountRef a = accounts.get(g.accountId());
				if (!"PAID_OFF".equals(a.status()) && !"SOLD".equals(a.status())) {
					accounts.endInterestSuspension(g.accountId(), g.interestEventId(), today);
				}
			}
			g.release(today, why.trim());
		}
		return group;
	}

	/** 공적 채무조정(가상) 확정: 확정일부터 조정 금리를 금리 이력에 추가 (현재 이율 이하만, R46) */
	@Transactional
	CollectionStop confirmRelief(long accountId, long stopId, InterestRate rate, LocalDate from) {
		CollectionStop s = find(accountId, stopId);
		if (!s.active() || s.reason() != CollectionStop.Reason.PUBLIC_WORKOUT) {
			throw new BusinessException("STOP_INVALID", "진행 중인 공적 채무조정(가상)만 확정합니다");
		}
		if (s.reliefFrom() != null) {
			throw new BusinessException("STOP_INVALID", "이미 조정 금리를 적용했습니다");
		}
		if (from == null || from.isAfter(businessDates.today()) || from.isBefore(s.effectiveDate())) {
			throw new BusinessException("STOP_INVALID", "확정일은 신청 접수일부터 업무일자 사이여야 합니다");
		}
		accounts.lowerRate(accountId, rate, from, "공적 채무조정(가상) 확정 " + s.groupNo());
		s.relief(rate.percent(), from);
		return s;
	}

	private CollectionStop find(long accountId, long id) {
		return stops.findById(id).filter(x -> x.accountId() == accountId)
				.orElseThrow(() -> new BusinessException("STOP_NOT_FOUND", "추심 중지 기록이 없습니다"));
	}
}
