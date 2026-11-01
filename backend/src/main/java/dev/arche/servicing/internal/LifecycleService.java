package dev.arche.servicing.internal;

import dev.arche.servicing.AdjustmentDecided;
import dev.arche.servicing.AdjustmentRequested;
import org.springframework.context.ApplicationEventPublisher;

import dev.arche.calendar.BusinessDates;
import dev.arche.common.BusinessException;
import dev.arche.common.Money;
import dev.arche.common.jpa.NumberGenerator;
import dev.arche.servicing.AccountRef;
import dev.arche.servicing.engine.AccountStatus;
import dev.arche.servicing.engine.LoanEvent;
import dev.arche.staff.CurrentStaff;
import dev.arche.staff.Permission;
import dev.arche.staff.StaffDirectory;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채권 생애주기 (정책서 R39~R44): 상각 후보·확정, 매각·환매·양수인 이전, 수기 조정·상계·상태 되돌리기 결재.
 * 금액이 바뀌는 처리는 모두 재생 이벤트를 남기고 계약 락 안에서 다시 재생한다 (ADR 0002·0005).
 */
@Service
class LifecycleService {

	/** 상각 후보: 기한이익상실 후 180일 경과 (정책서 5장, R39) */
	static final int CHARGE_OFF_DAYS_AFTER_ACCELERATION = 180;
	/** 상각 후보: 기준일 포함 직전 90일 입금 없음 (R39) */
	static final int CHARGE_OFF_NO_PAYMENT_DAYS = 90;

	record Candidate(long accountId, String accountNo, String productCode, Long assignedStaffId,
			LocalDate acceleratedOn, long daysSinceAcceleration, @Nullable LocalDate lastPaymentOn, long claim) {
	}

	record SaleCommand(String buyer, Money price, LocalDate noticeSentOn, String reason) {
	}

	record RequestCommand(AdjustmentRequest.Type type, long accountId, @Nullable Long sourceAccountId,
			@Nullable String setoffSource, long principalDown, long normalDown, long lateDown, long costDelta,
			long preAcqDown, long amount, @Nullable Long targetEventId, String reason) {
	}

	private final LoanAccountRepository accounts;
	private final AccountEventRepository events;
	private final LoanSaleRepository sales;
	private final AdjustmentRequestRepository requests;
	private final AccountReplayer replayer;
	private final BusinessDates businessDates;
	private final StaffDirectory staff;
	private final NumberGenerator numbers;
	private final ApplicationEventPublisher publisher;

	LifecycleService(LoanAccountRepository accounts, AccountEventRepository events, LoanSaleRepository sales,
			AdjustmentRequestRepository requests, AccountReplayer replayer, BusinessDates businessDates,
			StaffDirectory staff, NumberGenerator numbers, ApplicationEventPublisher publisher) {
		this.accounts = accounts;
		this.events = events;
		this.sales = sales;
		this.requests = requests;
		this.replayer = replayer;
		this.businessDates = businessDates;
		this.staff = staff;
		this.numbers = numbers;
		this.publisher = publisher;
	}

	// ------------------------------------------------------------------ 상각 (R39)

	/** 상각 후보가 아니면 그 이유, 후보면 empty */
	static Optional<String> chargeOffBlocker(LoanAccount a, @Nullable LocalDate lastPaymentOn, LocalDate today) {
		if (a.status() != AccountStatus.ACCELERATED || a.acceleratedOn() == null) {
			return Optional.of("기한이익상실 상태의 채권만 상각합니다");
		}
		long days = ChronoUnit.DAYS.between(a.acceleratedOn(), today);
		if (days < CHARGE_OFF_DAYS_AFTER_ACCELERATION) {
			return Optional.of("기한이익상실 후 " + days + "일 경과 (180일 이상이어야 함)");
		}
		if (lastPaymentOn != null && !lastPaymentOn.isBefore(today.minusDays(CHARGE_OFF_NO_PAYMENT_DAYS - 1))) {
			return Optional.of("최근 90일 안에 입금이 있습니다 (" + lastPaymentOn + ")");
		}
		return Optional.empty();
	}

	@Nullable
	LocalDate lastPaymentOn(long accountId, LocalDate today) {
		return events.findByAccountIdOrderById(accountId).stream()
				.filter(e -> e.active() && e.type() == AccountEvent.Type.PAYMENT && !e.eventDate().isAfter(today))
				.map(AccountEvent::eventDate).max(Comparator.naturalOrder()).orElse(null);
	}

	@Transactional(readOnly = true)
	List<Candidate> candidates() {
		LocalDate today = businessDates.today();
		List<Candidate> out = new ArrayList<>();
		for (LoanAccount a : accounts.findByStatusValue(AccountStatus.ACCELERATED.name())) {
			if (!staff.loanScope().allows(a.assignedStaffId())) {
				continue;
			}
			LocalDate last = lastPaymentOn(a.getId(), today);
			if (chargeOffBlocker(a, last, today).isEmpty()) {
				out.add(new Candidate(a.getId(), a.accountNo(), a.productCode(), a.assignedStaffId(),
						a.acceleratedOn(), ChronoUnit.DAYS.between(a.acceleratedOn(), today), last, claim(a)));
			}
		}
		return out;
	}

	static long claim(LoanAccount a) {
		return a.principalBalance() + a.unpaidNormalInterest() + a.accruedNormalInterest() + a.unpaidLateInterest()
				+ a.accruedLateInterest() + a.costBalance() + a.preAcqInterest();
	}

	@Transactional
	AccountRef chargeOff(long accountId, String reason) {
		requireReason(reason);
		LoanAccount a = lock(accountId);
		LocalDate today = businessDates.today();
		chargeOffBlocker(a, lastPaymentOn(accountId, today), today).ifPresent(why -> {
			throw new BusinessException("CHARGE_OFF_INVALID", "상각 후보가 아닙니다: " + why + " (R39)");
		});
		events.saveAndFlush(AccountEvent.lifecycle(accountId, AccountEvent.Type.CHARGE_OFF, today, reason));
		replay(a);
		if (a.status() != AccountStatus.CHARGED_OFF) {
			throw new BusinessException("CHARGE_OFF_INVALID", "상각을 확정하지 못했습니다");
		}
		return LoanAccountService.ref(a);
	}

	// ------------------------------------------------------------------ 매각·환매·이전 (R40·R41)

	@Transactional
	AccountRef sell(long accountId, SaleCommand c) {
		requireReason(c.reason());
		if (c.buyer() == null || c.buyer().isBlank()) {
			throw new BusinessException("SALE_INVALID", "양수인을 입력하세요");
		}
		if (!c.price().isPositive()) {
			throw new BusinessException("SALE_INVALID", "매각가는 0보다 커야 합니다");
		}
		LocalDate today = businessDates.today();
		if (c.noticeSentOn() == null || c.noticeSentOn().isAfter(today)) {
			throw new BusinessException("SALE_NOTICE_REQUIRED", "양도 통지 발송일(업무일자 이전)을 입력해야 매각할 수 있습니다 (정책서 5장)");
		}
		LoanAccount a = lock(accountId);
		if (a.status() != AccountStatus.ACCELERATED && a.status() != AccountStatus.CHARGED_OFF) {
			throw new BusinessException("SALE_INVALID", "상각 또는 기한이익상실 채권만 매각합니다 (정책서 5장)");
		}
		AccountEvent event = events
				.saveAndFlush(AccountEvent.lifecycle(accountId, AccountEvent.Type.SALE, today, c.reason()));
		sales.save(new LoanSale(accountId, event.getId(), c.buyer().trim(), c.price(), today, c.noticeSentOn(),
				c.reason()));
		replay(a);
		if (a.status() != AccountStatus.SOLD) {
			throw new BusinessException("SALE_INVALID", "매각을 확정하지 못했습니다");
		}
		return LoanAccountService.ref(a);
	}

	@Transactional
	AccountRef repurchase(long accountId, String reason) {
		requireReason(reason);
		LoanAccount a = lock(accountId);
		LoanSale sale = activeSale(a);
		LocalDate today = businessDates.today();
		if (today.isAfter(sale.repurchaseDeadline())) {
			throw new BusinessException("REPURCHASE_EXPIRED",
					"환매 기간(매각 후 6개월, " + sale.repurchaseDeadline() + "까지)이 지났습니다. 새 양수 등록으로 처리하세요 (R41)");
		}
		AccountEvent event = events
				.saveAndFlush(AccountEvent.lifecycle(accountId, AccountEvent.Type.REPURCHASE, today, reason));
		sale.repurchased(event.getId(), today, reason);
		replay(a);
		return LoanAccountService.ref(a);
	}

	@Transactional
	AccountRef transferToBuyer(long accountId, Money amount, String memo) {
		LoanAccount a = lock(accountId);
		activeSale(a);
		if (!amount.isPositive() || amount.won() > a.transferPending()) {
			throw new BusinessException("TRANSFER_INVALID",
					"이전 금액은 0보다 크고 이전 대상 " + Money.won(a.transferPending()) + " 이하여야 합니다 (R40)");
		}
		events.saveAndFlush(AccountEvent.buyerTransfer(accountId, businessDates.today(), amount,
				memo == null || memo.isBlank() ? "양수인 이전" : memo));
		replay(a);
		return LoanAccountService.ref(a);
	}

	private LoanSale activeSale(LoanAccount a) {
		if (a.status() != AccountStatus.SOLD) {
			throw new BusinessException("NOT_SOLD", "매각 중인 채권이 아닙니다");
		}
		return sales.findFirstByAccountIdAndStatusOrderByIdDesc(a.getId(), LoanSale.Status.ACTIVE)
				.orElseThrow(() -> new BusinessException("NOT_SOLD", "매각 기록이 없습니다"));
	}

	// ------------------------------------------------------------------ 결재 (R42~R44)

	@Transactional
	AdjustmentRequest request(RequestCommand c) {
		requireReason(c.reason());
		CurrentStaff me = staff.current();
		LocalDate today = businessDates.today();
		AdjustmentRequest r = new AdjustmentRequest(numbers.next("adjustment_no_seq", "AR"), c.type(),
				c.accountId(), c.reason().trim(), me.id(), today)
				.amounts(c.principalDown(), c.normalDown(), c.lateDown(), c.costDelta(), c.preAcqDown());
		if (c.type() == AdjustmentRequest.Type.SETOFF) {
			r.setoff(c.sourceAccountId() == null ? -1 : c.sourceAccountId(), c.setoffSource(), c.amount());
		}
		r.target(resolveTarget(c));
		validate(r);
		AdjustmentRequest saved = requests.save(r);
		publisher.publishEvent(new AdjustmentRequested(saved.getId(), saved.requestNo(), saved.accountId(),
				find(saved.accountId()).accountNo(), typeLabel(saved.type()), me.id(), me.name()));
		return saved;
	}

	/** 쪽지 등 사람이 읽는 곳에 쓰는 요청 종류 이름 */
	static String typeLabel(AdjustmentRequest.Type type) {
		return switch (type) {
			case ADJUSTMENT -> "수기 조정";
			case SETOFF -> "상계";
			case REVERT_CHARGE_OFF -> "상각 취소";
			case REVERT_SALE -> "매각 취소";
			case REVERT_SETOFF -> "상계 취소";
			case REVERT_PAYOFF -> "완제 취소";
		};
	}

	private void decided(AdjustmentRequest r, boolean approved, CurrentStaff me) {
		publisher.publishEvent(new AdjustmentDecided(r.getId(), r.requestNo(), r.accountId(),
				find(r.accountId()).accountNo(), typeLabel(r.type()), r.requestedBy(), approved, me.name()));
	}

	/** 되돌릴 이벤트: 상각·매각은 현재 유효한 것, 상계는 요청에 지정한 대상 계약의 SETOFF_IN */
	private @Nullable Long resolveTarget(RequestCommand c) {
		return switch (c.type()) {
			case REVERT_CHARGE_OFF -> latestActive(c.accountId(), AccountEvent.Type.CHARGE_OFF).map(AccountEvent::getId)
					.orElse(null);
			case REVERT_SALE -> latestActive(c.accountId(), AccountEvent.Type.SALE).map(AccountEvent::getId)
					.orElse(null);
			case REVERT_SETOFF -> c.targetEventId();
			default -> null;
		};
	}

	private Optional<AccountEvent> latestActive(long accountId, AccountEvent.Type type) {
		return events.findByAccountIdOrderById(accountId).stream().filter(e -> e.active() && e.type() == type)
				.reduce((x, y) -> y);
	}

	@Transactional
	AdjustmentRequest decide(long requestId, boolean approve) {
		AdjustmentRequest r = requests.findById(requestId)
				.orElseThrow(() -> new BusinessException("REQUEST_NOT_FOUND", "요청이 없습니다"));
		CurrentStaff me = staff.current();
		if (!me.has(Permission.LOAN_ADJUST_APPROVE)) {
			throw new BusinessException("FORBIDDEN", "승인 권한이 없습니다");
		}
		if (r.status() != AdjustmentRequest.Status.REQUESTED) {
			throw new BusinessException("REQUEST_CLOSED", "이미 처리된 요청입니다");
		}
		if (r.requestedBy() == me.id()) {
			throw new BusinessException("SAME_APPROVER", "작성자와 승인자는 달라야 합니다");
		}
		LocalDate today = businessDates.today();
		if (!approve) {
			r.rejected(me.id(), today);
			decided(r, false, me);
			return r;
		}
		validate(r);
		Long eventId = execute(r, today);
		r.applied(me.id(), today, eventId);
		decided(r, true, me);
		return r;
	}

	private void validate(AdjustmentRequest r) {
		LoanAccount a = find(r.accountId());
		AccountStatus s = a.status();
		switch (r.type()) {
			case ADJUSTMENT -> {
				requireOpen(a);
				if (r.principalDown() < 0 || r.normalDown() < 0 || r.lateDown() < 0 || r.preAcqDown() < 0) {
					throw new BusinessException("ADJUSTMENT_INVALID", "원금·이자·양수 전 미수이자는 감액만 할 수 있습니다 (R42)");
				}
				if (r.principalDown() + r.normalDown() + r.lateDown() + r.preAcqDown() == 0 && r.costDelta() == 0) {
					throw new BusinessException("ADJUSTMENT_INVALID", "조정할 금액을 입력하세요");
				}
			}
			case SETOFF -> {
				requireOpen(a);
				LoanAccount source = r.sourceAccountId() == null ? null
						: accounts.findById(r.sourceAccountId()).orElse(null);
				if (source == null || source.getId().equals(a.getId())) {
					throw new BusinessException("SETOFF_INVALID", "상계할 다른 계약(원천)을 지정하세요");
				}
				if (source.customerId() != a.customerId()) {
					throw new BusinessException("SETOFF_INVALID", "같은 고객의 계약끼리만 상계합니다 (R44)");
				}
				if (source.status() == AccountStatus.SOLD) {
					throw new BusinessException("SETOFF_INVALID", "매각된 계약의 돈은 상계할 수 없습니다");
				}
				long available = available(source, r.setoffSource());
				if (r.amount() <= 0 || r.amount() > available) {
					throw new BusinessException("SETOFF_INVALID",
							"상계액은 0보다 크고 원천 잔액 " + Money.won(available) + " 이하여야 합니다 (R44)");
				}
			}
			case REVERT_CHARGE_OFF -> {
				if (s != AccountStatus.CHARGED_OFF || r.targetEventId() == null) {
					throw new BusinessException("REVERT_INVALID", "상각 상태(매각 전)인 계약만 상각을 취소합니다 (R43)");
				}
			}
			case REVERT_SALE -> {
				if (s != AccountStatus.SOLD || r.targetEventId() == null) {
					throw new BusinessException("REVERT_INVALID", "매각 중인 계약만 매각을 취소합니다 (R43)");
				}
				boolean transferred = events.findByAccountIdOrderById(a.getId()).stream()
						.anyMatch(e -> e.active() && e.type() == AccountEvent.Type.BUYER_TRANSFER
								&& e.getId() > r.targetEventId());
				if (transferred) {
					throw new BusinessException("REVERT_INVALID", "양수인에게 이전한 금액이 있어 매각을 취소할 수 없습니다. 환매로 처리하세요 (R43)");
				}
			}
			case REVERT_SETOFF -> {
				AccountEvent in = r.targetEventId() == null ? null : events.findById(r.targetEventId()).orElse(null);
				if (in == null || in.type() != AccountEvent.Type.SETOFF_IN || !in.active()
						|| in.accountId() != a.getId()) {
					throw new BusinessException("REVERT_INVALID", "이 계약에 유효한 상계(대상)를 지정하세요 (R43)");
				}
			}
			case REVERT_PAYOFF -> {
				if (s != AccountStatus.PAID_OFF && s != AccountStatus.SOLD && s != AccountStatus.CHARGED_OFF) {
					throw new BusinessException("REVERT_INVALID", "완제·매각·상각 계약만 대상입니다 (R43)");
				}
				if (requests.findFirstByAccountIdAndTypeAndStatusOrderById(a.getId(),
						AdjustmentRequest.Type.REVERT_PAYOFF, AdjustmentRequest.Status.APPLIED).isPresent()) {
					throw new BusinessException("REVERT_INVALID", "이미 승인되어 아직 쓰지 않은 완제 취소가 있습니다");
				}
			}
		}
	}

	private static long available(LoanAccount source, @Nullable String setoffSource) {
		if ("REFUND".equals(setoffSource)) {
			return source.refundPending();
		}
		if ("SUSPENSE".equals(setoffSource)) {
			return source.suspenseBalance();
		}
		throw new BusinessException("SETOFF_INVALID", "상계 원천(REFUND·SUSPENSE)을 지정하세요");
	}

	private static void requireOpen(LoanAccount a) {
		if (a.status() == AccountStatus.PAID_OFF || a.status() == AccountStatus.SOLD) {
			throw new BusinessException("ACCOUNT_CLOSED", "완제·매각된 계약은 대상이 아닙니다");
		}
	}

	private @Nullable Long execute(AdjustmentRequest r, LocalDate today) {
		switch (r.type()) {
			case ADJUSTMENT -> {
				LoanAccount a = lock(r.accountId());
				AccountEvent e = events.saveAndFlush(AccountEvent.adjustment(a.getId(), today,
						Money.won(r.principalDown()), Money.won(r.normalDown()), Money.won(r.lateDown()),
						Money.won(r.costDelta()), Money.won(r.preAcqDown()), r.requestNo() + " " + r.reason()));
				replay(a);
				return e.getId();
			}
			case SETOFF -> {
				List<LoanAccount> locked = lockBoth(r.accountId(), r.sourceAccountId());
				LoanAccount target = pick(locked, r.accountId());
				LoanAccount source = pick(locked, r.sourceAccountId());
				String why = r.requestNo() + " " + r.reason();
				AccountEvent out = events.saveAndFlush(AccountEvent.setoffOut(source.getId(), today,
						Money.won(r.amount()), LoanEvent.SetoffSource.valueOf(r.setoffSource()), why));
				AccountEvent in = events.saveAndFlush(
						AccountEvent.setoffIn(target.getId(), today, Money.won(r.amount()), out.getId(), why));
				out.link(in.getId());
				events.flush();
				replay(source);
				replay(target);
				requireNonNegative(source);
				return in.getId();
			}
			case REVERT_CHARGE_OFF, REVERT_SALE -> {
				LoanAccount a = lock(r.accountId());
				AccountEvent e = events.findById(r.targetEventId()).orElseThrow();
				e.cancel(today, r.requestNo() + " " + r.reason());
				if (r.type() == AdjustmentRequest.Type.REVERT_SALE) {
					sales.findByAccountIdOrderByIdDesc(a.getId()).stream()
							.filter(x -> x.saleEventId() == e.getId()).forEach(LoanSale::cancelled);
				}
				events.flush();
				replay(a);
				return e.getId();
			}
			case REVERT_SETOFF -> {
				AccountEvent in = events.findById(r.targetEventId()).orElseThrow();
				AccountEvent out = in.linkedEventId() == null ? null : events.findById(in.linkedEventId()).orElse(null);
				List<LoanAccount> locked = lockBoth(in.accountId(), out == null ? null : out.accountId());
				String why = r.requestNo() + " " + r.reason();
				in.cancel(today, why);
				if (out != null) {
					out.cancel(today, why);
				}
				events.flush();
				for (LoanAccount a : locked) {
					replay(a);
				}
				return in.getId();
			}
			case REVERT_PAYOFF -> {
				return null;
			}
		}
		return null;
	}

	/** 상계 원천이 모자라게 되면 거절 (R44) */
	static void requireNonNegative(LoanAccount a) {
		if (a.suspenseBalance() < 0 || a.refundPending() < 0) {
			throw new BusinessException("SETOFF_SHORTAGE",
					a.accountNo() + "의 상계 원천(가수금·반환 대기)이 모자랍니다. 상계 취소(R43)를 먼저 하세요 (R44)");
		}
	}

	// ------------------------------------------------------------------ 공통

	private static void requireReason(String reason) {
		if (reason == null || reason.isBlank()) {
			throw new BusinessException("REASON_REQUIRED", "사유를 입력하세요");
		}
	}

	/** 교착을 피하려고 id 순서로 잠근다 */
	private List<LoanAccount> lockBoth(long a, @Nullable Long b) {
		List<LoanAccount> out = new ArrayList<>();
		if (b == null || b == a) {
			out.add(lock(a));
			return out;
		}
		out.add(lock(Math.min(a, b)));
		out.add(lock(Math.max(a, b)));
		return out;
	}

	private static LoanAccount pick(List<LoanAccount> list, long id) {
		return list.stream().filter(x -> x.getId() == id).findFirst().orElseThrow();
	}

	private LoanAccount lock(long id) {
		return accounts.lockById(id)
				.orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "계약이 없습니다: " + id));
	}

	private LoanAccount find(long id) {
		return accounts.findById(id)
				.orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "계약이 없습니다: " + id));
	}

	private void replay(LoanAccount a) {
		LocalDate today = businessDates.today();
		LocalDate asOf = a.asOf() != null && a.asOf().isAfter(today) ? a.asOf() : today;
		replayer.replay(a, replayer.terms(a), asOf, today, null);
	}
}
