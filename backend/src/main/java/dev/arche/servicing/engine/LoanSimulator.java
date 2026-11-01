package dev.arche.servicing.engine;

import dev.arche.common.BusinessCalendar;
import dev.arche.common.DateRange;
import dev.arche.common.InterestRate;
import dev.arche.common.Money;
import dev.arche.common.RepaymentMethod;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * 계약 재생 엔진. 실행(양수)일부터 기준일까지 하루씩 진행하며 다음 순서로 처리한다.
 *
 * <ol>
 * <li>하루 시작: 유예 기한이 지난 회차의 연체 판정 → 기한이익상실 통지 취소·상실 → 새 상실 사유 판정 (R24 ③·④)</li>
 * <li>금리 이력 변경일이면 스케줄 재산출 (R20)</li>
 * <li>비용 적재 → 입금(기산일 = 그날) → 약정일 가수금 충당 → 중도상환 요청 (R22) → 약정일 변경 (R21)</li>
 * </ol>
 *
 * 이자는 정산 이벤트에서만 확정한다 (R1). 정상이자 기준금액은 원금 잔액, 가산이자 기준금액은
 * 미납 원금 합계(상실·만기 후에는 전체 잔액)이며 각각 바뀌는 날에만 구간을 나눈다 (R2·R12).
 */
public final class LoanSimulator {

	/**
	 * @param detectFrom   이 날짜보다 이른 상실 사유는 이 날짜를 사유 발생일로 기록한다 (R29: 소급 취소로 과거에 생겼어야
	 *                     할 사유는 처리일을 사유 발생일로 봄). null이면 사유가 생긴 날 그대로
	 * @param knownNotices 이미 저장된 통지. 같은 사유 발생일이면 그 통지(실제 도달일 포함)를 이어 쓴다
	 */
	public record Options(@Nullable LocalDate detectFrom, List<KnownNotice> knownNotices) {

		public static final Options DEFAULT = new Options(null, List.of());

		public Options {
			knownNotices = List.copyOf(knownNotices);
		}
	}

	private final BusinessCalendar calendar;

	public LoanSimulator(BusinessCalendar calendar) {
		this.calendar = calendar;
	}

	public SimulationResult simulate(LoanTerms terms, List<LoanEvent> events, LocalDate asOf) {
		return simulate(terms, events, asOf, Options.DEFAULT);
	}

	public SimulationResult simulate(LoanTerms terms, List<LoanEvent> events, LocalDate asOf, Options options) {
		return new Run(terms, events, options).run(asOf);
	}

	/** 은행 거래일자 → 기산일 (R13: 휴일 유예 영업일 안의 납부는 약정일 납부) */
	public LocalDate valueDateOf(LocalDate bankDate, List<LocalDate> dueDates) {
		for (LocalDate due : dueDates) {
			if (bankDate.isAfter(due) && !bankDate.isAfter(calendar.onOrNextBusinessDay(due))) {
				return due;
			}
		}
		return bankDate;
	}

	private static final class Inst {
		final int seq;
		final LocalDate periodStart;
		final LocalDate dueDate;
		final Money principalDue;
		final Money plannedInterest;
		final boolean grace;
		Money principalPaid = Money.ZERO;
		boolean checked;
		boolean overdue;
		boolean resolved;

		Inst(int seq, LocalDate periodStart, LocalDate dueDate, Money principalDue, Money plannedInterest,
				boolean grace) {
			this.seq = seq;
			this.periodStart = periodStart;
			this.dueDate = dueDate;
			this.principalDue = principalDue;
			this.plannedInterest = plannedInterest;
			this.grace = grace;
		}

		Money unpaid() {
			return principalDue.minus(principalPaid);
		}
	}

	private static final class Notice {
		final LocalDate reasonDate;
		final LocalDate sentOn;
		final LocalDate deemedArrivalOn;
		final @Nullable LocalDate actualArrivalOn;
		final LocalDate accelerationDate;
		NoticeStatus status = NoticeStatus.PENDING;
		@Nullable LocalDate closedOn;

		Notice(LocalDate reasonDate, LocalDate sentOn, LocalDate deemedArrivalOn, @Nullable LocalDate actualArrivalOn,
				LocalDate accelerationDate) {
			this.reasonDate = reasonDate;
			this.sentOn = sentOn;
			this.deemedArrivalOn = deemedArrivalOn;
			this.actualArrivalOn = actualArrivalOn;
			this.accelerationDate = accelerationDate;
		}

		AccelerationNoticeView view() {
			return new AccelerationNoticeView(reasonDate, sentOn, deemedArrivalOn, actualArrivalOn, accelerationDate,
					status, closedOn);
		}
	}

	private record Settled(Money normal, Money late) {
		static final Settled NONE = new Settled(Money.ZERO, Money.ZERO);
	}

	/** 매각 기간에 들어온 돈 (환매 시 충당) */
	private record Received(String ref, Money amount) {
	}

	/** 매각 때 양수인에게 넘긴 잔액 (R40·R41) */
	private static final class SoldState {
		final LocalDate soldOn;
		final Money principal;
		final Money normal;
		final Money late;
		final Money cost;
		final Money preAcq;
		final List<Received> received = new ArrayList<>();

		SoldState(LocalDate soldOn, Money principal, Money normal, Money late, Money cost, Money preAcq) {
			this.soldOn = soldOn;
			this.principal = principal;
			this.normal = normal;
			this.late = late;
			this.cost = cost;
			this.preAcq = preAcq;
		}
	}

	private final class Run {

		private final LoanTerms terms;
		/** 채무조정 체결 후에는 원리금균등으로 바뀐다 */
		private RepaymentMethod method;
		private final Options options;

		private final List<LoanEvent.Payment> payments = new ArrayList<>();
		private final Set<String> processedPayments = new HashSet<>();
		private final List<LoanEvent.PrepaymentRequest> prepayments = new ArrayList<>();
		private final List<LoanEvent.DueDayChange> dueDayChanges = new ArrayList<>();
		private final List<LoanEvent.CostCharge> costs = new ArrayList<>();
		private final Set<LocalDate> rateChangeDates = new HashSet<>();

		private List<Inst> schedule = new ArrayList<>();
		private final List<Installment> previousVersions = new ArrayList<>();
		private int scheduleVersion = 1;
		private @Nullable Money annuity;

		private Money principal;
		private Money suspense = Money.ZERO;
		private Money cost = Money.ZERO;
		private Money preAcq = Money.ZERO;
		private Money unpaidNormal = Money.ZERO;
		private Money unpaidLate = Money.ZERO;
		private Money totalPaid = Money.ZERO;
		private Money refundPending = Money.ZERO;
		private LocalDate lastSettled;
		private boolean sameDayInterestCharged;
		private boolean accelerated;
		private @Nullable LocalDate acceleratedOn;
		private @Nullable LocalDate paidOffOn;

		private final List<Notice> notices = new ArrayList<>();
		private @Nullable Notice active;
		private final List<KnownNotice> unusedKnown;

		private final List<Posting> postings = new ArrayList<>();
		private final List<String> warnings = new ArrayList<>();

		private final List<LoanEvent.Workout> workoutEvents = new ArrayList<>();
		private final List<WorkoutState> workouts = new ArrayList<>();
		/** 체결일 당일, 미감면 이자 납부를 기다리는 채무조정 */
		private @Nullable WorkoutState pendingWorkout;
		/** 체결된 채무조정 (3회차 연속 미납 시 감면액 부활 대상) */
		private @Nullable WorkoutState activeWorkout;

		// 채권 생애주기 (R39~R46)
		private final List<LoanEvent.ChargeOff> chargeOffs = new ArrayList<>();
		private final List<LoanEvent.Sale> sales = new ArrayList<>();
		private final List<LoanEvent.Repurchase> repurchases = new ArrayList<>();
		private final List<LoanEvent.BuyerTransfer> buyerTransfers = new ArrayList<>();
		private final List<LoanEvent.Adjustment> adjustments = new ArrayList<>();
		private final List<LoanEvent.SetoffOut> setoffOuts = new ArrayList<>();
		private final List<LoanEvent.SetoffIn> setoffIns = new ArrayList<>();
		private final List<LoanEvent.InterestSuspension> suspensions = new ArrayList<>();
		private @Nullable LocalDate chargedOffOn;
		/** 매각 중이면 매각 때 넘긴 잔액 (환매 시 되살림) */
		private @Nullable SoldState sold;
		private Money transferPending = Money.ZERO;

		Run(LoanTerms terms, List<LoanEvent> events, Options options) {
			this.terms = terms;
			this.method = terms.method();
			this.options = options;
			this.principal = terms.principal();
			this.lastSettled = terms.disbursedOn();
			this.unusedKnown = new ArrayList<>(options.knownNotices());
			this.unusedKnown.sort(Comparator.comparing(KnownNotice::reasonDate));
			for (LoanEvent e : events) {
				switch (e) {
					case LoanEvent.Payment p -> payments.add(p);
					case LoanEvent.PrepaymentRequest p -> prepayments.add(p);
					case LoanEvent.DueDayChange c -> dueDayChanges.add(c);
					case LoanEvent.CostCharge c -> costs.add(c);
					case LoanEvent.Workout w -> workoutEvents.add(w);
					case LoanEvent.ChargeOff c -> chargeOffs.add(c);
					case LoanEvent.Sale s -> sales.add(s);
					case LoanEvent.Repurchase r -> repurchases.add(r);
					case LoanEvent.BuyerTransfer t -> buyerTransfers.add(t);
					case LoanEvent.Adjustment a -> adjustments.add(a);
					case LoanEvent.SetoffOut s -> setoffOuts.add(s);
					case LoanEvent.SetoffIn s -> setoffIns.add(s);
					case LoanEvent.InterestSuspension s -> suspensions.add(s);
				}
			}
			for (RatePeriod p : terms.ratePeriods()) {
				if (p.validFrom().isAfter(terms.disbursedOn())) {
					rateChangeDates.add(p.validFrom());
				}
			}
		}

		SimulationResult run(LocalDate asOf) {
			if (asOf.isBefore(terms.disbursedOn())) {
				throw new IllegalArgumentException("기준일이 실행일보다 이릅니다: " + asOf);
			}
			start();
			for (LocalDate d = terms.disbursedOn(); !d.isAfter(asOf); d = d.plusDays(1)) {
				day(d);
			}
			for (KnownNotice k : unusedKnown) {
				if (!k.reasonDate().isAfter(asOf)) {
					Notice n = newNotice(k.reasonDate(), k.actualArrivalOn());
					n.status = NoticeStatus.VOID;
					notices.add(n);
				}
			}
			return result(asOf);
		}

		// ---------------------------------------------------------------- 시작

		private void start() {
			if (method == RepaymentMethod.ACQUIRED) {
				Acquisition acq = terms.acquisition();
				preAcq = acq.preAcqInterest();
				cost = acq.cost();
				accelerated = true;
				acceleratedOn = acq.acquiredOn();
				post(terms.disbursedOn(), PostingType.ACQUIRE, "ACQUIRE", principal.plus(preAcq).plus(cost)).add();
				return;
			}
			int n = terms.termMonths();
			int graceCount = method == RepaymentMethod.GRACE_THEN_INSTALLMENT ? terms.graceMonths() : 0;
			InterestRate rate = terms.initialRate();
			if (method == RepaymentMethod.EQUAL_INSTALLMENT || method == RepaymentMethod.GRACE_THEN_INSTALLMENT) {
				annuity = ScheduleMath.annuity(principal, rate, n - graceCount);
			}
			List<LocalDate> dues = ScheduleMath.monthlyDueDates(
					ScheduleMath.firstDueDate(terms.disbursedOn(), terms.dueDay()), terms.dueDay(), n);
			List<Integer> seqs = new ArrayList<>();
			for (int i = 1; i <= n; i++) {
				seqs.add(i);
			}
			schedule = plan(seqs, dues, terms.disbursedOn(), principal, graceCount, annuity, d -> rate, null);
			post(terms.disbursedOn(), PostingType.DISBURSE, "DISBURSE", principal).add();
		}

		/**
		 * 회차 계획. keepPrincipal이 있으면 회차 원금을 그대로 두고 날짜·계획 이자만 다시 계산 (R21 원금균등 등).
		 * 마지막 회차는 남은 잔액 전체.
		 */
		private List<Inst> plan(List<Integer> seqs, List<LocalDate> dues, LocalDate start, Money balance,
				int graceCount, @Nullable Money a, Function<LocalDate, InterestRate> rateAt,
				@Nullable List<Money> keepPrincipal) {
			List<Inst> out = new ArrayList<>();
			int n = dues.size();
			int amortCount = Math.max(1, n - graceCount);
			Money equalPrincipal = Money.won(balance.won() / amortCount);
			LocalDate prev = start;
			Money bal = balance;
			for (int i = 0; i < n; i++) {
				LocalDate due = dues.get(i);
				Money interest = ScheduleMath.periodInterest(bal, DateRange.of(prev, due), rateAt);
				boolean last = i == n - 1;
				boolean grace = i < graceCount;
				Money p;
				if (keepPrincipal != null) {
					p = last ? bal : keepPrincipal.get(i).min(bal);
				} else {
					p = switch (method) {
						case EQUAL_INSTALLMENT, GRACE_THEN_INSTALLMENT -> grace ? Money.ZERO
								: last ? bal : a.minus(interest).max(Money.ZERO).min(bal);
						case EQUAL_PRINCIPAL -> last ? bal : equalPrincipal.min(bal);
						case BULLET -> last ? bal : Money.ZERO;
						case REVOLVING, ACQUIRED -> Money.ZERO;
					};
				}
				out.add(new Inst(seqs.get(i), prev, due, p, interest, grace));
				bal = bal.minus(p);
				prev = due;
			}
			return out;
		}

		/** following 회차들을 새 계획으로 교체하고 스케줄 버전을 올린다. 이전 버전은 보관 (guide 3.3) */
		private void replaceTail(List<Inst> following, List<Inst> replanned) {
			for (Inst i : schedule) {
				previousVersions.add(view(i, InstallmentStatus.REPLACED));
			}
			List<Inst> next = new ArrayList<>(schedule.subList(0, schedule.size() - following.size()));
			next.addAll(replanned);
			schedule = next;
			scheduleVersion++;
		}

		private @Nullable LocalDate maturity() {
			return schedule.isEmpty() ? null : schedule.getLast().dueDate;
		}

		private boolean matured(LocalDate d) {
			LocalDate m = maturity();
			return m != null && d.isAfter(m);
		}

		private LocalDate graceEnd(Inst i) {
			return calendar.onOrNextBusinessDay(i.dueDate);
		}

		// ---------------------------------------------------------------- 하루

		private void day(LocalDate d) {
			startOfDay(d);
			if (rateChangeDates.contains(d)) {
				applyRateChange(d);
			}
			for (LoanEvent.CostCharge c : costs) {
				if (c.chargedOn().equals(d)) {
					cost = cost.plus(c.amount());
					post(d, PostingType.COST_CHARGE, c.ref(), c.amount()).add();
				}
			}
			// 환매·매각은 그날 입금보다 먼저: 매각일(포함) 이후 입금은 이전 대상, 환매일 입금은 되살린 잔액에 충당 (R40·R41)
			for (LoanEvent.Repurchase r : repurchases) {
				if (r.on().equals(d)) {
					repurchase(d, r);
				}
			}
			for (LoanEvent.Sale s : sales) {
				if (s.on().equals(d)) {
					sell(d, s);
				}
			}
			for (LoanEvent.Workout w : workoutEvents) {
				if (w.contractedOn().equals(d)) {
					beginWorkout(d, w);
				}
			}
			for (LoanEvent.Payment p : payments) {
				if (!processedPayments.contains(p.ref()) && valueDate(p.bankDate()).equals(d)) {
					processedPayments.add(p.ref());
					receive(d, p.ref(), p.amount(), PostingType.REPAYMENT, PostingType.SUSPENSE_IN, true);
				}
			}
			for (LoanEvent.SetoffIn s : setoffIns) {
				if (s.on().equals(d)) {
					receive(d, s.ref(), s.amount(), PostingType.SETOFF, PostingType.SETOFF, false);
				}
			}
			if (pendingWorkout != null) {
				concludeWorkout(d);
			} else if (paidOffOn == null && !accelerated && !method.settlesOnPayment() && suspense.isPositive()
					&& schedule.stream().anyMatch(i -> i.dueDate.equals(d))) {
				allocate(d, suspense, false, PostingType.SUSPENSE_APPLY, "DUE-" + d, true);
			}
			for (LoanEvent.PrepaymentRequest r : prepayments) {
				if (r.requestedOn().equals(d)) {
					prepay(d, r);
				}
			}
			for (LoanEvent.DueDayChange c : dueDayChanges) {
				if (c.requestedOn().equals(d)) {
					changeDueDay(c);
				}
			}
			// 하루 끝: 수기 조정·상계 원천·양수인 이전·상각 (그날 입금을 반영한 잔액 기준)
			for (LoanEvent.Adjustment a : adjustments) {
				if (a.on().equals(d)) {
					adjust(d, a);
				}
			}
			for (LoanEvent.SetoffOut s : setoffOuts) {
				if (s.on().equals(d)) {
					setoffOut(d, s);
				}
			}
			for (LoanEvent.BuyerTransfer t : buyerTransfers) {
				if (t.on().equals(d)) {
					transferToBuyer(d, t);
				}
			}
			for (LoanEvent.ChargeOff c : chargeOffs) {
				if (c.on().equals(d)) {
					chargeOff(d, c);
				}
			}
		}

		private LocalDate valueDate(LocalDate bankDate) {
			if (method == RepaymentMethod.ACQUIRED) {
				return bankDate;
			}
			return valueDateOf(bankDate, schedule.stream().map(i -> i.dueDate).toList());
		}

		private void startOfDay(LocalDate d) {
			for (Inst i : schedule) {
				if (!i.checked && graceEnd(i).isBefore(d)) {
					i.checked = true;
					if (!settledNow(i)) {
						i.overdue = true;
					}
				}
			}
			if (active != null) {
				if (!delinquent()) {
					active.status = NoticeStatus.CANCELLED;
					active.closedOn = d;
					active = null;
				} else if (d.equals(active.accelerationDate)) {
					active.status = NoticeStatus.EXECUTED;
					active.closedOn = d;
					active = null;
					accelerate(d);
				}
			}
			if (activeWorkout != null && !accelerated && paidOffOn == null && consecutiveUnresolved(3)) {
				restoreWorkout(d);
			}
			// 채무조정 중에는 R4 대신 R9(3회차 연속 미납 → 상실 복귀)를 적용한다 (R35)
			if (active == null && !accelerated && activeWorkout == null && paidOffOn == null
					&& method != RepaymentMethod.ACQUIRED && !matured(d) && reasonExists(d)) {
				KnownNotice known = unusedKnown.stream().filter(k -> k.reasonDate().equals(d)).findFirst()
						.orElse(null);
				boolean knownLater = unusedKnown.stream().anyMatch(k -> k.reasonDate().isAfter(d));
				if (known != null) {
					unusedKnown.remove(known);
					openNotice(newNotice(d, known.actualArrivalOn()));
				} else if (!knownLater && (options.detectFrom() == null || !d.isBefore(options.detectFrom()))) {
					openNotice(newNotice(d, null));
				}
			}
		}

		private void openNotice(Notice n) {
			notices.add(n);
			active = n;
		}

		/** R15: 사유 발생일에 발송(휴일이면 다음 영업일), 도달 = 발송 + 2영업일, 상실 = 도달 + 10영업일 */
		private Notice newNotice(LocalDate reasonDate, @Nullable LocalDate actualArrival) {
			LocalDate sent = calendar.onOrNextBusinessDay(reasonDate);
			LocalDate deemed = calendar.plusBusinessDays(sent, 2);
			LocalDate arrival = actualArrival != null ? actualArrival : deemed;
			return new Notice(reasonDate, sent, deemed, actualArrival, calendar.plusBusinessDays(arrival, 10));
		}

		private void accelerate(LocalDate d) {
			accelerated = true;
			acceleratedOn = d;
			if (suspense.isPositive()) {
				allocate(d, suspense, true, PostingType.SUSPENSE_APPLY, "ACCELERATE-" + d, true);
			}
		}

		/** 기한이익상실 사유 (R4·R14·R33) */
		private boolean reasonExists(LocalDate d) {
			if (method.isInstallment()) {
				for (int k = 0; k + 1 < schedule.size(); k++) {
					if (unresolved(schedule.get(k)) && unresolved(schedule.get(k + 1))) {
						return true;
					}
				}
				return false;
			}
			return schedule.stream().anyMatch(i -> unresolved(i) && d.isAfter(i.dueDate.plusMonths(1)));
		}

		private boolean unresolved(Inst i) {
			return i.overdue && !i.resolved;
		}

		private boolean delinquent() {
			return schedule.stream().anyMatch(this::unresolved);
		}

		/** 회차 납입액(원금 + 약정일까지 이자)이 모두 충당됐는지 (R16) */
		private boolean settledNow(Inst i) {
			if (!i.unpaid().isZero()) {
				return false;
			}
			return i.principalDue.isPositive()
					|| (!lastSettled.isBefore(i.dueDate) && unpaidNormal.isZero() && unpaidLate.isZero());
		}

		private void updateResolution() {
			for (Inst i : schedule) {
				if (unresolved(i) && settledNow(i)) {
					i.resolved = true;
				}
			}
		}

		// ---------------------------------------------------------------- 이자

		/** 상각일 이후(R39)와 이자 부과 중단 기간(R46)에는 정상이자·가산이자를 붙이지 않는다 */
		private boolean interestStopped(LocalDate day) {
			if (chargedOffOn != null && !day.isBefore(chargedOffOn)) {
				return true;
			}
			for (LoanEvent.InterestSuspension s : suspensions) {
				if (!day.isBefore(s.from()) && (s.until() == null || day.isBefore(s.until()))) {
					return true;
				}
			}
			return false;
		}

		private InterestAccrual.Basis normalBasis(LocalDate day) {
			if (interestStopped(day)) {
				return new InterestAccrual.Basis(Money.ZERO, InterestRate.ZERO);
			}
			return new InterestAccrual.Basis(principal, terms.rateAt(day));
		}

		private InterestAccrual.Basis lateBasis(LocalDate day) {
			if (method == RepaymentMethod.ACQUIRED || interestStopped(day)) {
				return new InterestAccrual.Basis(Money.ZERO, InterestRate.ZERO);
			}
			InterestRate spread = terms.rateAt(day).lateSpread();
			if ((accelerated && !day.isBefore(acceleratedOn)) || matured(day)) {
				return new InterestAccrual.Basis(principal, spread);
			}
			Money base = Money.ZERO;
			for (Inst i : schedule) {
				if (i.overdue && !i.dueDate.isAfter(day)) {
					base = base.plus(i.unpaid());
				}
			}
			return new InterestAccrual.Basis(base, spread);
		}

		private Settled accrued(LocalDate to) {
			if (!to.isAfter(lastSettled)) {
				if (to.equals(terms.disbursedOn()) && !sameDayInterestCharged && method != RepaymentMethod.ACQUIRED) {
					// 당일 실행·당일 상환은 1일분 이자 (정책서 2장)
					return new Settled(terms.rateAt(to).interest(principal, 1), Money.ZERO);
				}
				return Settled.NONE;
			}
			DateRange period = DateRange.of(lastSettled, to);
			return new Settled(InterestAccrual.accrue(period, this::normalBasis).total(),
					InterestAccrual.accrue(period, this::lateBasis).total());
		}

		/** 정산 이벤트: 마지막 정산일부터 d까지의 이자를 확정 (R1) */
		private Settled settle(LocalDate d) {
			Settled s = accrued(d);
			if (!d.isAfter(lastSettled) && s.normal().isPositive()) {
				sameDayInterestCharged = true;
			}
			unpaidNormal = unpaidNormal.plus(s.normal());
			unpaidLate = unpaidLate.plus(s.late());
			if (d.isAfter(lastSettled)) {
				lastSettled = d;
			}
			return s;
		}

		// ---------------------------------------------------------------- 입금·충당

		/**
		 * 돈이 들어옴 (입금 또는 상계 R44). immediate = 즉시 충당 거래 유형, held = 가수금 보관 거래 유형.
		 * 매각 중이면 충당하지 않고 양수인 이전 대상으로 남긴다 (R40).
		 */
		private void receive(LocalDate d, String ref, Money amount, PostingType immediate, PostingType held,
				boolean bankPayment) {
			if (sold != null) {
				transferPending = transferPending.plus(amount);
				sold.received.add(new Received(ref, amount));
				post(d, PostingType.SALE_TRANSFER, ref, amount).add();
				return;
			}
			totalPaid = totalPaid.plus(amount);
			if (paidOffOn != null) {
				refundPending = refundPending.plus(amount);
				post(d, PostingType.REFUND, ref, amount).refund(amount).add();
				return;
			}
			if (pendingWorkout != null) {
				// 채무조정 체결일 입금은 미감면 이자·비용에만 충당하고, 남는 돈은 가수금 (R36)
				allocate(d, amount, false, immediate, ref, false, true);
				return;
			}
			boolean dueAll = method.settlesOnPayment() || accelerated || matured(d);
			if (dueAll || delinquent()) {
				allocate(d, amount, dueAll, immediate, ref, false);
			} else {
				// 연체 없는 계약의 약정일 전 입금은 가수금 (정책서 4장, R32)
				suspense = suspense.plus(amount);
				post(d, held, ref, amount).suspense(amount).add();
			}
		}

		/**
		 * 충당 순서 (정책서 4장): 비용 → 가산이자 → 정상이자 → 연체·도래 원금(오래된 회차부터) → (전액 도래면) 남은 원금.
		 * 매입채권은 비용 → 양수 후 이자 → 양수 전 미수이자 → 원금 (1-1장).
		 */
		private void allocate(LocalDate d, Money cash, boolean principalAll, PostingType type, String ref,
				boolean fromSuspense) {
			allocate(d, cash, principalAll, type, ref, fromSuspense, false);
		}

		private void allocate(LocalDate d, Money cash, boolean principalAll, PostingType type, String ref,
				boolean fromSuspense, boolean interestOnly) {
			Settled s = settle(d);
			Waterfall pot = new Waterfall(cash);
			Money paidCost = pot.take(cost);
			cost = cost.minus(paidCost);
			Money paidLate = pot.take(unpaidLate);
			unpaidLate = unpaidLate.minus(paidLate);
			Money paidNormal = pot.take(unpaidNormal);
			unpaidNormal = unpaidNormal.minus(paidNormal);
			Money paidPreAcq = pot.take(preAcq);
			preAcq = preAcq.minus(paidPreAcq);

			List<Posting.InstallmentAllocation> allocations = new ArrayList<>();
			Money paidPrincipal = Money.ZERO;
			for (Inst i : schedule) {
				if (pot.left().isZero() || interestOnly) {
					break;
				}
				if ((principalAll || !i.dueDate.isAfter(d)) && i.unpaid().isPositive()) {
					Money t = pot.take(i.unpaid());
					i.principalPaid = i.principalPaid.plus(t);
					paidPrincipal = paidPrincipal.plus(t);
					allocations.add(new Posting.InstallmentAllocation(i.seq, t));
				}
			}
			if (principalAll) {
				paidPrincipal = paidPrincipal.plus(pot.take(principal.minus(paidPrincipal)));
			}
			principal = principal.minus(paidPrincipal);

			Money suspenseDelta;
			Money refund = Money.ZERO;
			Money used = cash.minus(pot.left());
			if (fromSuspense) {
				suspenseDelta = Money.ZERO.minus(used);
				suspense = suspense.plus(suspenseDelta);
			} else if (principal.isZero() && pot.left().isPositive()) {
				refund = pot.left();
				refundPending = refundPending.plus(refund);
				suspenseDelta = Money.ZERO;
			} else {
				suspenseDelta = pot.left();
				suspense = suspense.plus(pot.left());
			}
			if (used.isPositive() || !fromSuspense) {
				post(d, type, ref, fromSuspense ? used : cash).principal(paidPrincipal).normal(paidNormal)
						.late(paidLate).cost(paidCost).preAcq(paidPreAcq).suspense(suspenseDelta).refund(refund)
						.accrued(s.normal(), s.late()).allocations(allocations).add();
			}
			updateResolution();
			checkPaidOff(d);
		}

		private void checkPaidOff(LocalDate d) {
			if (paidOffOn == null && sold == null && principal.isZero() && unpaidNormal.isZero() && unpaidLate.isZero()
					&& cost.isZero() && preAcq.isZero()) {
				paidOffOn = d;
				active = null;
				if (suspense.isPositive()) {
					// 과입금은 반환 대기 (정책서 4장)
					refundPending = refundPending.plus(suspense);
					post(d, PostingType.REFUND, "PAYOFF-" + d, suspense).suspense(Money.ZERO.minus(suspense))
							.refund(suspense).add();
					suspense = Money.ZERO;
				}
			}
		}

		// ---------------------------------------------------------------- 중도상환 (R7·R22)

		private Money fee(Money repaid, LocalDate d) {
			LocalDate m = maturity();
			if (m == null || !d.isBefore(m)) {
				return Money.ZERO;
			}
			return ScheduleMath.prepaymentFee(repaid, terms.prepaymentFeeRate(), terms.disbursedOn(), m, d);
		}

		private void prepay(LocalDate d, LoanEvent.PrepaymentRequest r) {
			if (paidOffOn != null || accelerated || method.settlesOnPayment()) {
				warnings.add(r.ref() + ": 중도상환 요청을 처리할 수 없는 상태 (완제·상실·자유상환)");
				return;
			}
			if (suspense.isPositive()) {
				allocate(d, suspense, false, PostingType.SUSPENSE_APPLY, r.ref() + "-ARREARS", true);
			} else {
				settle(d);
			}
			boolean arrears = !unpaidNormal.isZero() || !unpaidLate.isZero() || !cost.isZero()
					|| schedule.stream().anyMatch(i -> !i.dueDate.isAfter(d) && i.unpaid().isPositive());
			if (arrears) {
				warnings.add(r.ref() + ": 연체금을 먼저 충당하지 못해 중도상환하지 않음");
				return;
			}
			Money target = r.principal() == null ? principal : r.principal().min(principal);
			Money p = target;
			if (p.plus(fee(p, d)).isGreaterThan(suspense)) {
				p = affordable(d, target);
				warnings.add(r.ref() + ": 가수금 부족으로 상환원금을 " + p + "으로 줄임");
			}
			if (!p.isPositive()) {
				return;
			}
			Money fee = fee(p, d);
			suspense = suspense.minus(p.plus(fee));
			principal = principal.minus(p);
			post(d, PostingType.PREPAYMENT, r.ref(), p.plus(fee)).principal(p).fee(fee)
					.suspense(Money.ZERO.minus(p.plus(fee))).add();
			List<Inst> following = schedule.stream().filter(i -> i.dueDate.isAfter(d)).toList();
			if (principal.isZero()) {
				for (Inst i : schedule) {
					previousVersions.add(view(i, InstallmentStatus.REPLACED));
				}
				schedule = new ArrayList<>(schedule.subList(0, schedule.size() - following.size()));
				scheduleVersion++;
				checkPaidOff(d);
				return;
			}
			int graceCount = (int) following.stream().filter(i -> i.grace).count();
			if (method == RepaymentMethod.EQUAL_INSTALLMENT || method == RepaymentMethod.GRACE_THEN_INSTALLMENT) {
				annuity = ScheduleMath.annuity(principal, terms.rateAt(d), following.size() - graceCount);
			}
			replaceTail(following, plan(following.stream().map(i -> i.seq).toList(),
					following.stream().map(i -> i.dueDate).toList(), d, principal, graceCount, annuity,
					terms::rateAt, null));
		}

		/** 가수금으로 갚을 수 있는 최대 원금 (원금 + 수수료 ≤ 가수금) */
		private Money affordable(LocalDate d, Money cap) {
			long lo = 0;
			long hi = Math.min(cap.won(), suspense.won());
			while (lo < hi) {
				long mid = (lo + hi + 1) / 2;
				Money m = Money.won(mid);
				if (m.plus(fee(m, d)).isGreaterThan(suspense)) {
					hi = mid - 1;
				} else {
					lo = mid;
				}
			}
			return Money.won(lo);
		}

		// ---------------------------------------------------------------- 채무조정 (R9·R23)

		private final class WorkoutState {
			final LoanEvent.Workout event;
			Money waivedLate = Money.ZERO;
			Money waivedNormal = Money.ZERO;
			Money waivedPrincipal = Money.ZERO;
			Money required = Money.ZERO;
			boolean concluded;
			@Nullable LocalDate restoredOn;

			WorkoutState(LoanEvent.Workout event) {
				this.event = event;
			}

			WorkoutView view() {
				return new WorkoutView(event.ref(), event.contractedOn(), concluded, waivedLate, waivedNormal,
						waivedPrincipal, required, restoredOn);
			}
		}

		/** 체결일까지 이자 확정 → 감면 (미납액을 넘는 감면은 미납액까지만) */
		private void beginWorkout(LocalDate d, LoanEvent.Workout w) {
			WorkoutState ws = new WorkoutState(w);
			workouts.add(ws);
			if (paidOffOn != null || method == RepaymentMethod.ACQUIRED || pendingWorkout != null || sold != null
					|| chargedOffOn != null) {
				warnings.add(w.ref() + ": 채무조정을 체결할 수 없는 상태 (완제·매입채권·상각·매각)");
				return;
			}
			Settled s = settle(d);
			ws.waivedLate = w.waiveLate().min(unpaidLate);
			ws.waivedNormal = w.waiveNormal().min(unpaidNormal);
			ws.waivedPrincipal = w.waivePrincipal().min(principal);
			unpaidLate = unpaidLate.minus(ws.waivedLate);
			unpaidNormal = unpaidNormal.minus(ws.waivedNormal);
			principal = principal.minus(ws.waivedPrincipal);
			ws.required = unpaidLate.plus(unpaidNormal).plus(cost).minus(suspense).max(Money.ZERO);
			post(d, PostingType.WAIVER, w.ref(), ws.waivedLate.plus(ws.waivedNormal).plus(ws.waivedPrincipal))
					.principal(ws.waivedPrincipal).normal(ws.waivedNormal).late(ws.waivedLate)
					.accrued(s.normal(), s.late()).add();
			pendingWorkout = ws;
			if (suspense.isPositive()) {
				allocate(d, suspense, false, PostingType.SUSPENSE_APPLY, w.ref() + "-SUSPENSE", true, true);
			}
		}

		/** 체결일 입금을 받은 뒤: 미감면 이자·비용을 다 받았으면 새 스케줄, 아니면 체결하지 않음 (R23) */
		private void concludeWorkout(LocalDate d) {
			WorkoutState ws = pendingWorkout;
			pendingWorkout = null;
			LoanEvent.Workout w = ws.event;
			if (!unpaidLate.isZero() || !unpaidNormal.isZero() || !cost.isZero()) {
				unpaidLate = unpaidLate.plus(ws.waivedLate);
				unpaidNormal = unpaidNormal.plus(ws.waivedNormal);
				principal = principal.plus(ws.waivedPrincipal);
				post(d, PostingType.ADJUSTMENT, w.ref() + "-UNPAID",
						Money.ZERO.minus(ws.waivedLate.plus(ws.waivedNormal).plus(ws.waivedPrincipal)))
						.principal(Money.ZERO.minus(ws.waivedPrincipal)).normal(Money.ZERO.minus(ws.waivedNormal))
						.late(Money.ZERO.minus(ws.waivedLate)).add();
				warnings.add(w.ref() + ": 미감면 이자 " + ws.required + "을 체결일에 받지 못해 채무조정을 체결하지 않음 (R23)");
				return;
			}
			ws.concluded = true;
			if (active != null) {
				active.status = NoticeStatus.CANCELLED;
				active.closedOn = d;
				active = null;
			}
			accelerated = false;
			acceleratedOn = null;
			method = RepaymentMethod.EQUAL_INSTALLMENT;
			for (Inst i : schedule) {
				previousVersions.add(view(i, InstallmentStatus.REPLACED));
			}
			List<Integer> seqs = new ArrayList<>();
			for (int i = 1; i <= w.installments(); i++) {
				seqs.add(i);
			}
			annuity = ScheduleMath.annuity(principal, terms.rateAt(d), w.installments());
			schedule = plan(seqs, ScheduleMath.monthlyDueDates(ScheduleMath.firstDueDate(d, w.dueDay()), w.dueDay(),
					w.installments()), d, principal, 0, annuity, terms::rateAt, null);
			scheduleVersion++;
			activeWorkout = ws;
			checkPaidOff(d);
		}

		/** 조정 후 3회차 연속 미납: 통지 없이 감면액을 되살리고 기한이익상실 상태로 복귀 (R9·R35) */
		private void restoreWorkout(LocalDate d) {
			WorkoutState ws = activeWorkout;
			activeWorkout = null;
			ws.restoredOn = d;
			unpaidLate = unpaidLate.plus(ws.waivedLate);
			unpaidNormal = unpaidNormal.plus(ws.waivedNormal);
			principal = principal.plus(ws.waivedPrincipal);
			post(d, PostingType.ADJUSTMENT, ws.event.ref() + "-RESTORE",
					Money.ZERO.minus(ws.waivedLate.plus(ws.waivedNormal).plus(ws.waivedPrincipal)))
					.principal(Money.ZERO.minus(ws.waivedPrincipal)).normal(Money.ZERO.minus(ws.waivedNormal))
					.late(Money.ZERO.minus(ws.waivedLate)).add();
			accelerate(d);
		}

		private boolean consecutiveUnresolved(int n) {
			int run = 0;
			for (Inst i : schedule) {
				run = unresolved(i) ? run + 1 : 0;
				if (run >= n) {
					return true;
				}
			}
			return false;
		}

		// ---------------------------------------------------------------- 상각·매각·환매 (R39~R41)

		private Money claimTotal() {
			return principal.plus(unpaidNormal).plus(unpaidLate).plus(cost).plus(preAcq);
		}

		/** 상각 확정: 확정일까지 이자 정산 → 장부 제거액 기록. 원금 잔액(청구 잔액)은 그대로, 확정일부터 이자 중단 (R39) */
		private void chargeOff(LocalDate d, LoanEvent.ChargeOff c) {
			if (paidOffOn != null || sold != null || chargedOffOn != null || !accelerated) {
				warnings.add(c.ref() + ": 상각할 수 없는 상태 (기한이익상실 채권만, 완제·매각·이미 상각 제외)");
				return;
			}
			Settled s = settle(d);
			chargedOffOn = d;
			post(d, PostingType.CHARGE_OFF, c.ref(), claimTotal()).principal(principal).normal(unpaidNormal)
					.late(unpaidLate).cost(cost).preAcq(preAcq).accrued(s.normal(), s.late()).add();
		}

		/** 매각: 매각일까지 이자 정산 → 잔액을 양수인에게 넘김. 남은 가수금도 이전 대상 (R40) */
		private void sell(LocalDate d, LoanEvent.Sale e) {
			if (paidOffOn != null || sold != null || !accelerated) {
				warnings.add(e.ref() + ": 매각할 수 없는 상태 (상각·기한이익상실 채권만)");
				return;
			}
			Settled s = settle(d);
			SoldState st = new SoldState(e.on(), principal, unpaidNormal, unpaidLate, cost, preAcq);
			Money total = claimTotal();
			principal = Money.ZERO;
			unpaidNormal = Money.ZERO;
			unpaidLate = Money.ZERO;
			cost = Money.ZERO;
			preAcq = Money.ZERO;
			post(d, PostingType.SALE, e.ref(), total).principal(st.principal).normal(st.normal).late(st.late)
					.cost(st.cost).preAcq(st.preAcq).accrued(s.normal(), s.late()).add();
			sold = st;
			if (suspense.isPositive()) {
				Money held = suspense;
				suspense = Money.ZERO;
				transferPending = transferPending.plus(held);
				st.received.add(new Received(e.ref() + "-SUSPENSE", held));
				post(d, PostingType.SALE_TRANSFER, e.ref() + "-SUSPENSE", held).suspense(Money.ZERO.minus(held)).add();
			}
		}

		/**
		 * 환매: 넘긴 잔액을 되살린다(매각 기간 이자 없음). 매각 기간 입금은 환매일에 채무자 입금으로 충당하고 이전 대상에서
		 * 뺀다. 이미 양수인에게 넘긴 금액은 양수인과 환매가로 정산한다(시스템 밖) (R41)
		 */
		private void repurchase(LocalDate d, LoanEvent.Repurchase r) {
			SoldState st = sold;
			if (st == null) {
				warnings.add(r.ref() + ": 매각 중인 채권이 아님");
				return;
			}
			sold = null;
			lastSettled = d.isAfter(lastSettled) ? d : lastSettled;
			principal = st.principal;
			unpaidNormal = st.normal;
			unpaidLate = st.late;
			cost = st.cost;
			preAcq = st.preAcq;
			Money total = claimTotal();
			post(d, PostingType.REPURCHASE, r.ref(), Money.ZERO.minus(total))
					.principal(Money.ZERO.minus(st.principal)).normal(Money.ZERO.minus(st.normal))
					.late(Money.ZERO.minus(st.late)).cost(Money.ZERO.minus(st.cost))
					.preAcq(Money.ZERO.minus(st.preAcq)).add();
			if (transferPending.isPositive()) {
				Money release = transferPending;
				transferPending = Money.ZERO;
				post(d, PostingType.SALE_TRANSFER, r.ref() + "-RELEASE", Money.ZERO.minus(release)).add();
			}
			for (Received x : st.received) {
				totalPaid = totalPaid.plus(x.amount());
				if (paidOffOn != null) {
					refundPending = refundPending.plus(x.amount());
					post(d, PostingType.REFUND, x.ref() + "-BUYBACK", x.amount()).refund(x.amount()).add();
				} else {
					allocate(d, x.amount(), true, PostingType.REPAYMENT, x.ref() + "-BUYBACK", false);
				}
			}
		}

		private void transferToBuyer(LocalDate d, LoanEvent.BuyerTransfer t) {
			if (t.amount().isGreaterThan(transferPending)) {
				warnings.add(t.ref() + ": 이전 대상 금액(" + transferPending + ")보다 많이 이전함");
			}
			transferPending = transferPending.minus(t.amount());
			post(d, PostingType.SALE_TRANSFER, t.ref(), Money.ZERO.minus(t.amount())).add();
		}

		// ---------------------------------------------------------------- 수기 조정·상계 (R42·R44)

		/** 그날까지 이자 정산 → 항목별 감액(미납액까지), 비용 증감. 원금 감액은 마지막 회차부터 (R42) */
		private void adjust(LocalDate d, LoanEvent.Adjustment a) {
			if (paidOffOn != null || sold != null) {
				warnings.add(a.ref() + ": 조정할 수 없는 상태 (완제·매각)");
				return;
			}
			Settled s = settle(d);
			Money late = a.lateDown().max(Money.ZERO).min(unpaidLate);
			Money normal = a.normalDown().max(Money.ZERO).min(unpaidNormal);
			Money pre = a.preAcqDown().max(Money.ZERO).min(preAcq);
			Money princ = a.principalDown().max(Money.ZERO).min(principal);
			Money costField = a.costDelta().isNegative() ? Money.ZERO.minus(a.costDelta()).min(cost)
					: Money.ZERO.minus(a.costDelta());
			unpaidLate = unpaidLate.minus(late);
			unpaidNormal = unpaidNormal.minus(normal);
			preAcq = preAcq.minus(pre);
			cost = cost.minus(costField);
			List<Posting.InstallmentAllocation> allocations = new ArrayList<>();
			Money left = princ;
			for (int k = schedule.size() - 1; k >= 0 && left.isPositive(); k--) {
				Inst i = schedule.get(k);
				if (i.unpaid().isPositive()) {
					Money t = left.min(i.unpaid());
					i.principalPaid = i.principalPaid.plus(t);
					allocations.add(new Posting.InstallmentAllocation(i.seq, t));
					left = left.minus(t);
				}
			}
			principal = principal.minus(princ);
			post(d, PostingType.ADJUSTMENT, a.ref(), princ.plus(normal).plus(late).plus(pre).plus(costField))
					.principal(princ).normal(normal).late(late).preAcq(pre).cost(costField)
					.accrued(s.normal(), s.late()).allocations(allocations).add();
			updateResolution();
			checkPaidOff(d);
		}

		/** 상계 원천: 반환 대기 또는 가수금에서 뺀다. 모자라면 경고 (저장 계층이 거절) (R44) */
		private void setoffOut(LocalDate d, LoanEvent.SetoffOut s) {
			if (s.source() == LoanEvent.SetoffSource.REFUND) {
				refundPending = refundPending.minus(s.amount());
				post(d, PostingType.SETOFF, s.ref(), s.amount()).refund(Money.ZERO.minus(s.amount())).add();
				if (refundPending.isNegative()) {
					warnings.add(s.ref() + ": 상계액이 반환 대기 금액보다 큼");
				}
			} else {
				suspense = suspense.minus(s.amount());
				post(d, PostingType.SETOFF, s.ref(), s.amount()).suspense(Money.ZERO.minus(s.amount())).add();
				if (suspense.isNegative()) {
					warnings.add(s.ref() + ": 상계액이 가수금보다 큼");
				}
			}
		}

		// ---------------------------------------------------------------- 조건 변경 (R20·R21)

		private void applyRateChange(LocalDate validFrom) {
			if (paidOffOn != null || accelerated) {
				return;
			}
			if (method != RepaymentMethod.EQUAL_INSTALLMENT && method != RepaymentMethod.GRACE_THEN_INSTALLMENT) {
				return;
			}
			int c = -1;
			for (int k = 0; k < schedule.size(); k++) {
				if (schedule.get(k).dueDate.isAfter(validFrom)) {
					c = k;
					break;
				}
			}
			if (c < 0 || c + 1 >= schedule.size()) {
				return;
			}
			List<Inst> following = List.copyOf(schedule.subList(c + 1, schedule.size()));
			Money scheduledBalance = sumPrincipalDue(following);
			int graceCount = (int) following.stream().filter(i -> i.grace).count();
			annuity = ScheduleMath.annuity(scheduledBalance, terms.rateAt(validFrom), following.size() - graceCount);
			replaceTail(following, plan(following.stream().map(i -> i.seq).toList(),
					following.stream().map(i -> i.dueDate).toList(), schedule.get(c).dueDate, scheduledBalance,
					graceCount, annuity, terms::rateAt, null));
		}

		private void changeDueDay(LoanEvent.DueDayChange change) {
			if (paidOffOn != null || accelerated || method == RepaymentMethod.ACQUIRED) {
				warnings.add(change.ref() + ": 약정일을 바꿀 수 없는 상태");
				return;
			}
			int k = -1;
			for (int x = 0; x < schedule.size(); x++) {
				if (!schedule.get(x).dueDate.isBefore(change.requestedOn())) {
					k = x;
					break;
				}
			}
			if (k < 0 || k + 1 >= schedule.size()) {
				return;
			}
			List<Inst> following = List.copyOf(schedule.subList(k + 1, schedule.size()));
			LocalDate oldFirst = following.getFirst().dueDate;
			YearMonth month = YearMonth.from(oldFirst);
			if (ScheduleMath.dueDateIn(month, change.newDueDay()).isBefore(oldFirst)) {
				month = month.plusMonths(1);
			}
			List<LocalDate> dues = ScheduleMath.monthlyDueDates(ScheduleMath.dueDateIn(month, change.newDueDay()),
					change.newDueDay(), following.size());
			int graceCount = (int) following.stream().filter(i -> i.grace).count();
			boolean annuityMethod = method == RepaymentMethod.EQUAL_INSTALLMENT
					|| method == RepaymentMethod.GRACE_THEN_INSTALLMENT;
			List<Money> keep = annuityMethod ? null : following.stream().map(i -> i.principalDue).toList();
			replaceTail(following, plan(following.stream().map(i -> i.seq).toList(), dues,
					schedule.get(k).dueDate, sumPrincipalDue(following), graceCount, annuity, terms::rateAt, keep));
		}

		private Money sumPrincipalDue(List<Inst> insts) {
			Money sum = Money.ZERO;
			for (Inst i : insts) {
				sum = sum.plus(i.principalDue);
			}
			return sum;
		}

		// ---------------------------------------------------------------- 결과

		private PostingBuilder post(LocalDate d, PostingType type, String ref, Money amount) {
			return new PostingBuilder(d, type, ref, amount);
		}

		private final class PostingBuilder {
			private final LocalDate d;
			private final PostingType type;
			private final String ref;
			private final Money amount;
			private Money principalPaid = Money.ZERO;
			private Money normal = Money.ZERO;
			private Money late = Money.ZERO;
			private Money paidCost = Money.ZERO;
			private Money fee = Money.ZERO;
			private Money paidPreAcq = Money.ZERO;
			private Money suspenseDelta = Money.ZERO;
			private Money refund = Money.ZERO;
			private Money normalAccrued = Money.ZERO;
			private Money lateAccrued = Money.ZERO;
			private List<Posting.InstallmentAllocation> allocations = List.of();

			PostingBuilder(LocalDate d, PostingType type, String ref, Money amount) {
				this.d = d;
				this.type = type;
				this.ref = ref;
				this.amount = amount;
			}

			PostingBuilder principal(Money v) {
				principalPaid = v;
				return this;
			}

			PostingBuilder normal(Money v) {
				normal = v;
				return this;
			}

			PostingBuilder late(Money v) {
				late = v;
				return this;
			}

			PostingBuilder cost(Money v) {
				paidCost = v;
				return this;
			}

			PostingBuilder fee(Money v) {
				fee = v;
				return this;
			}

			PostingBuilder preAcq(Money v) {
				paidPreAcq = v;
				return this;
			}

			PostingBuilder suspense(Money v) {
				suspenseDelta = v;
				return this;
			}

			PostingBuilder refund(Money v) {
				refund = v;
				return this;
			}

			PostingBuilder accrued(Money normalV, Money lateV) {
				normalAccrued = normalV;
				lateAccrued = lateV;
				return this;
			}

			PostingBuilder allocations(List<Posting.InstallmentAllocation> v) {
				allocations = List.copyOf(v);
				return this;
			}

			void add() {
				postings.add(new Posting(postings.size() + 1, d, type, ref, amount, principalPaid, normal, late,
						paidCost, fee, paidPreAcq, suspenseDelta, refund, normalAccrued, lateAccrued, principal,
						allocations));
			}
		}

		private Installment view(Inst i, @Nullable InstallmentStatus forced) {
			InstallmentStatus status;
			if (forced != null) {
				status = forced;
			} else if (unresolved(i)) {
				status = InstallmentStatus.OVERDUE;
			} else if (i.resolved || (i.checked && !i.overdue) || settledNow(i)) {
				status = InstallmentStatus.PAID;
			} else if (i.principalPaid.isPositive()) {
				status = InstallmentStatus.PARTIALLY_PAID;
			} else {
				status = InstallmentStatus.PLANNED;
			}
			return new Installment(scheduleVersion, i.seq, i.periodStart, i.dueDate, graceEnd(i), i.principalDue,
					i.plannedInterest, i.principalPaid, status);
		}

		private SimulationResult result(LocalDate asOf) {
			Settled acc = paidOffOn != null ? Settled.NONE : accrued(asOf);
			LocalDate since = schedule.stream().filter(this::unresolved).map(i -> i.dueDate).min(LocalDate::compareTo)
					.orElse(null);
			Money feeToday = paidOffOn != null ? Money.ZERO : fee(principal, asOf);
			Money payoff = paidOffOn != null ? Money.ZERO
					: cost.plus(unpaidLate).plus(acc.late()).plus(unpaidNormal).plus(acc.normal()).plus(preAcq)
							.plus(principal).plus(feeToday).minus(suspense).max(Money.ZERO);
			AccountStatus status = paidOffOn != null ? AccountStatus.PAID_OFF
					: sold != null ? AccountStatus.SOLD
					: chargedOffOn != null ? AccountStatus.CHARGED_OFF
					: accelerated ? AccountStatus.ACCELERATED
							: activeWorkout != null ? AccountStatus.WORKOUT
									: since != null ? AccountStatus.DELINQUENT : AccountStatus.NORMAL;
			return new SimulationResult(asOf, status, principal, suspense, cost, preAcq, unpaidNormal, unpaidLate,
					acc.normal(), acc.late(), lastSettled, since, acceleratedOn, maturity(), annuity, scheduleVersion,
					schedule.stream().map(i -> view(i, null)).toList(), List.copyOf(previousVersions),
					List.copyOf(postings), notices.stream().map(Notice::view).toList(), totalPaid, refundPending,
					feeToday, payoff, paidOffOn, List.copyOf(warnings),
					workouts.stream().map(WorkoutState::view).toList(), chargedOffOn,
					sold == null ? null : sold.soldOn, transferPending);
		}
	}
}
