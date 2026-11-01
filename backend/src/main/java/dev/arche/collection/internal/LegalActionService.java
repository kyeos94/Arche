package dev.arche.collection.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.payment.LegalActionLink;
import dev.arche.collection.internal.LegalActionRules.Stage;
import dev.arche.collection.internal.LegalActionRules.Type;
import dev.arche.common.BusinessException;
import dev.arche.common.Money;
import dev.arche.common.jpa.NumberGenerator;
import dev.arche.servicing.AccountRef;
import dev.arche.servicing.LoanAccounts;
import dev.arche.staff.CurrentStaff;
import dev.arche.staff.StaffDirectory;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

interface LegalActionRepository extends JpaRepository<LegalAction, Long> {

	List<LegalAction> findByAccountIdOrderByIdDesc(long accountId);

	List<LegalAction> findTop200ByOrderByIdDesc();
}

interface LegalActionStageRepository extends JpaRepository<LegalActionStage, Long> {

	List<LegalActionStage> findByLegalActionIdOrderById(long legalActionId);
}

interface LegalActionCostRepository extends JpaRepository<LegalActionCost, Long> {

	List<LegalActionCost> findByLegalActionIdOrderById(long legalActionId);
}

/**
 * 법조치 등록·단계 변경·비용 (R51·R52). 신청·확정 단계는 소멸시효 사유를 servicing에 기록하고,
 * 취하·각하 등은 그 사유를 무효로 한다. 비용은 servicing의 COST 적재 흐름을 그대로 쓴다.
 */
@Service
class LegalActionService implements LegalActionLink {

	record OpenCommand(Type type, LocalDate filedOn, @Nullable String courtName, @Nullable String caseRef,
			@Nullable String targetDesc, @Nullable Long claimAmount, @Nullable String memo) {
	}

	private final LegalActionRepository actions;
	private final LegalActionStageRepository stages;
	private final LegalActionCostRepository costs;
	private final LoanAccounts accounts;
	private final BusinessDates businessDates;
	private final StaffDirectory staff;
	private final NumberGenerator numbers;
	private final Clock clock;

	LegalActionService(LegalActionRepository actions, LegalActionStageRepository stages,
			LegalActionCostRepository costs, LoanAccounts accounts, BusinessDates businessDates, StaffDirectory staff,
			NumberGenerator numbers, Clock clock) {
		this.actions = actions;
		this.stages = stages;
		this.costs = costs;
		this.accounts = accounts;
		this.businessDates = businessDates;
		this.staff = staff;
		this.numbers = numbers;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Long> accountIdOf(long legalActionId) {
		return actions.findById(legalActionId).map(LegalAction::accountId);
	}

	@Transactional
	LegalAction open(AccountRef account, OpenCommand c) {
		if (account.paidOffOn() != null) {
			throw new BusinessException("ACCOUNT_CLOSED", "완제된 계약에는 법조치를 등록할 수 없습니다");
		}
		requireNotFuture(c.filedOn());
		if (c.claimAmount() != null && c.claimAmount() < 0) {
			throw new BusinessException("AMOUNT_INVALID", "청구금액은 0 이상이어야 합니다");
		}
		LegalAction a = actions.save(new LegalAction(numbers.next("legal_action_no_seq", "LA"), account.id(),
				c.type(), c.filedOn(), trim(c.courtName()), trim(c.caseRef()), trim(c.targetDesc()), c.claimAmount(),
				trim(c.memo())));
		long eventId = accounts.recordLimitationEvent(account.id(), LegalActionRules.interruptionOnFiling(c.type()),
				c.filedOn(), a.actionNo() + " " + LegalActionRules.label(c.type()) + " 신청");
		saveStage(a, Stage.FILED, c.filedOn(), c.memo(), eventId);
		return a;
	}

	@Transactional
	LegalAction move(long actionId, Stage to, LocalDate on, @Nullable String memo) {
		LegalAction a = action(actionId);
		if (!LegalActionRules.canMove(a.type(), a.status(), to)) {
			throw new BusinessException("STAGE_INVALID", LegalActionRules.label(a.type()) + " " + a.status() + " → "
					+ to + " 로 바꿀 수 없습니다 (R51)");
		}
		requireNotFuture(on);
		List<LegalActionStage> history = stages.findByLegalActionIdOrderById(a.getId());
		LocalDate last = history.isEmpty() ? a.filedOn() : history.getLast().stageDate();
		if (on.isBefore(last)) {
			throw new BusinessException("DATE_INVALID", "앞 단계 날짜(" + last + ")보다 이를 수 없습니다");
		}
		Stage from = a.status();
		Long eventId = null;
		var event = LegalActionRules.eventOnStage(a.type(), to);
		if (event.isPresent()) {
			eventId = accounts.recordLimitationEvent(a.accountId(), event.get(), on,
					a.actionNo() + " " + LegalActionRules.label(a.type()) + " 확정");
		}
		if (LegalActionRules.voidsInterruption(from, to)) {
			for (LegalActionStage s : history) {
				if (s.stage() == Stage.FILED && s.limitationEventId() != null) {
					accounts.voidLimitationEvent(a.accountId(), s.limitationEventId(), on,
							a.actionNo() + " " + to + " (R51)");
				}
			}
		}
		a.moveTo(to, on);
		saveStage(a, to, on, memo, eventId);
		return a;
	}

	@Transactional
	LegalActionCost charge(long actionId, LegalActionCost.CostType type, Money amount, LocalDate incurredOn,
			@Nullable String memo) {
		LegalAction a = action(actionId);
		if (!amount.isPositive()) {
			throw new BusinessException("AMOUNT_INVALID", "비용은 0보다 커야 합니다");
		}
		requireNotFuture(incurredOn);
		LocalDate today = businessDates.today();
		accounts.chargeCost(a.accountId(), amount,
				a.actionNo() + " " + LegalActionRules.label(a.type()) + " " + type + (memo == null ? "" : " " + memo));
		CurrentStaff me = staff.current();
		return costs.save(new LegalActionCost(a.getId(), type, amount, incurredOn, today, trim(memo), me.id(),
				OffsetDateTime.now(clock), me.loginId()));
	}

	@Transactional(readOnly = true)
	List<LegalAction> of(long accountId) {
		return actions.findByAccountIdOrderByIdDesc(accountId);
	}

	@Transactional(readOnly = true)
	List<LegalAction> recent() {
		return actions.findTop200ByOrderByIdDesc();
	}

	List<LegalActionStage> stages(LegalAction a) {
		return stages.findByLegalActionIdOrderById(a.getId());
	}

	List<LegalActionCost> costs(LegalAction a) {
		return costs.findByLegalActionIdOrderById(a.getId());
	}

	LegalAction action(long id) {
		return actions.findById(id).orElseThrow(() -> new BusinessException("LEGAL_ACTION_NOT_FOUND", "법조치가 없습니다"));
	}

	private void saveStage(LegalAction a, Stage stage, LocalDate on, @Nullable String memo, @Nullable Long eventId) {
		CurrentStaff me = staff.current();
		stages.save(new LegalActionStage(a.getId(), stage, on, trim(memo), eventId, me.id(), OffsetDateTime.now(clock),
				me.loginId()));
	}

	private void requireNotFuture(LocalDate date) {
		if (date == null || date.isAfter(businessDates.today())) {
			throw new BusinessException("DATE_INVALID", "업무일자 이후 날짜는 기록할 수 없습니다");
		}
	}

	private static @Nullable String trim(@Nullable String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}
}
