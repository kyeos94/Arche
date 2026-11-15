package dev.arche.operation.internal;

import dev.arche.common.BusinessException;
import dev.arche.servicing.AccountRef;
import dev.arche.servicing.LoanAccounts;
import dev.arche.staff.CurrentStaff;
import dev.arche.staff.StaffDirectory;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

interface DailyClosingFailureRepository extends JpaRepository<DailyClosingFailure, Long> {

	List<DailyClosingFailure> findByBusinessDateOrderById(LocalDate businessDate);

	boolean existsByAccountIdAndResolvedAtIsNull(long accountId);

	boolean existsByBusinessDateAndAccountIdAndResolvedAtIsNull(LocalDate businessDate, long accountId);

	/** 해결 표시: 그 계약의 미해결 기록 전부. DB 트리거가 허용하는 유일한 수정 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = "update daily_closing_failure set resolved_at = :at, resolved_by = :by"
			+ " where account_id = :accountId and resolved_at is null", nativeQuery = true)
	int resolve(@Param("accountId") long accountId, @Param("at") OffsetDateTime at, @Param("by") String by);
}

/**
 * 일마감 판정 실패 기록과 재판정 (R76).
 */
@Service
class ClosingFailureService {

	private static final Logger log = LoggerFactory.getLogger(ClosingFailureService.class);

	record FailureView(DailyClosingFailure failure, String accountNo) {
	}

	private final DailyClosingFailureRepository failures;
	private final LoanAccounts accounts;
	private final StaffDirectory staff;
	private final Clock clock;

	ClosingFailureService(DailyClosingFailureRepository failures, LoanAccounts accounts, StaffDirectory staff,
			Clock clock) {
		this.failures = failures;
		this.accounts = accounts;
		this.staff = staff;
		this.clock = clock;
	}

	/**
	 * 마감 D의 판정 실패를 기록한다. 마감이 중간에 실패해 같은 D를 다시 실행하면 같은 계약의 미해결 기록을 또 만들지 않는다.
	 *
	 * @return 기록한 실패 계약 수 (중복 제외)
	 */
	@Transactional
	int record(LocalDate businessDate, List<LoanAccounts.JudgeFailure> judged) {
		OffsetDateTime now = OffsetDateTime.now(clock);
		Set<Long> seen = new HashSet<>();
		for (LoanAccounts.JudgeFailure f : judged) {
			if (seen.add(f.accountId())
					&& !failures.existsByBusinessDateAndAccountIdAndResolvedAtIsNull(businessDate, f.accountId())) {
				failures.save(new DailyClosingFailure(businessDate, f, now));
			}
		}
		return seen.size();
	}

	@Transactional(readOnly = true)
	List<FailureView> ofDate(LocalDate businessDate) {
		return failures.findByBusinessDateOrderById(businessDate).stream()
				.map(f -> new FailureView(f, accountNo(f.accountId()))).toList();
	}

	/**
	 * 재판정: 계약을 현재 업무일자까지 다시 재생하고, 성공하면 그 계약의 미해결 실패 기록을 모두 해결로 표시한다. 계약 락을 잡은 뒤
	 * 해결 표시를 하므로, 같은 계약의 동시 재판정은 앞의 요청이 끝날 때까지 기다렸다가 ALREADY_RESOLVED가 된다.
	 * 재생이 다시 실패하면 아무것도 바꾸지 않고 오류를 돌려준다.
	 */
	@Transactional
	AccountRef rejudge(long accountId) {
		if (!failures.existsByAccountIdAndResolvedAtIsNull(accountId)) {
			throw new BusinessException("FAILURE_NOT_FOUND", "해결할 일마감 실패 기록이 없습니다");
		}
		AccountRef ref;
		try {
			ref = accounts.rejudge(accountId);
		} catch (BusinessException e) {
			throw e;
		} catch (RuntimeException e) {
			log.error("재판정 실패 계약 id {}", accountId, e);
			throw new BusinessException("REJUDGE_FAILED", "재판정에 실패했습니다: " + summary(e));
		}
		String by = staff.currentIfAny().map(CurrentStaff::loginId).orElse("batch");
		if (failures.resolve(accountId, OffsetDateTime.now(clock), by) == 0) {
			throw new BusinessException("ALREADY_RESOLVED", "다른 사용자가 먼저 재판정했습니다");
		}
		log.info("재판정 완료 계약 {} ({})", ref.accountNo(), by);
		return ref;
	}

	private String accountNo(long accountId) {
		try {
			return accounts.get(accountId).accountNo();
		} catch (BusinessException e) {
			return "#" + accountId;
		}
	}

	private static String summary(RuntimeException e) {
		String m = e.getMessage();
		if (m == null || m.isBlank()) {
			return e.getClass().getSimpleName();
		}
		String first = m.strip().lines().findFirst().orElse(m);
		return first.length() <= 200 ? first : first.substring(0, 199) + "…";
	}
}
