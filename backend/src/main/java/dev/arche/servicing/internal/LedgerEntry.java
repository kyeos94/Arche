package dev.arche.servicing.internal;

import dev.arche.common.Money;
import dev.arche.servicing.engine.Posting;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.Immutable;

/**
 * 원장 거래. 추가 전용: UPDATE·DELETE는 DB 트리거가 막고, 정정은 REVERSAL 거래로만 한다 (CLAUDE.md, R26).
 */
@Entity
@Immutable
@Table(name = "ledger_entry")
class LedgerEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	private long accountId;
	private String type;
	private LocalDate valueDate;
	private LocalDate businessDate;
	private OffsetDateTime postedAt;
	private String sourceRef;
	private long amount;
	private long principal;
	private long normalInterest;
	private long lateInterest;
	private long cost;
	private long fee;
	private long preAcqInterest;
	private long suspense;
	private long refund;
	private long normalAccrued;
	private long lateAccrued;
	private long principalBalanceAfter;
	private Long paymentId;
	private Long correctionId;
	private Long reversalOfId;
	private OffsetDateTime createdAt;
	private String createdBy;

	@OneToMany(cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
	@JoinColumn(name = "ledger_entry_id", nullable = false, updatable = false)
	private List<LedgerAllocation> allocations = new ArrayList<>();

	protected LedgerEntry() {
	}

	static LedgerEntry of(long accountId, Posting p, Long paymentId, Long correctionId, LocalDate businessDate,
			OffsetDateTime now, String actor) {
		LedgerEntry e = new LedgerEntry();
		e.accountId = accountId;
		e.type = p.type().name();
		e.valueDate = p.valueDate();
		e.businessDate = businessDate;
		e.postedAt = now;
		e.sourceRef = p.sourceRef();
		e.amount = p.amount().won();
		e.principal = p.principal().won();
		e.normalInterest = p.normalInterest().won();
		e.lateInterest = p.lateInterest().won();
		e.cost = p.cost().won();
		e.fee = p.fee().won();
		e.preAcqInterest = p.preAcqInterest().won();
		e.suspense = p.suspense().won();
		e.refund = p.refund().won();
		e.normalAccrued = p.normalAccrued().won();
		e.lateAccrued = p.lateAccrued().won();
		e.principalBalanceAfter = p.principalBalanceAfter().won();
		e.paymentId = paymentId;
		e.correctionId = correctionId;
		e.createdAt = now;
		e.createdBy = actor;
		addAllocations(e, p);
		return e;
	}

	private static void addAllocations(LedgerEntry e, Posting p) {
		for (Posting.InstallmentAllocation a : p.allocations()) {
			e.allocations.add(new LedgerAllocation(a.installmentSeq(), "PRINCIPAL", a.principal().won()));
		}
		long allocatedPrincipal = p.allocations().stream().mapToLong(a -> a.principal().won()).sum();
		addIfNonZero(e, "PRINCIPAL", p.principal().won() - allocatedPrincipal);
		addIfNonZero(e, "NORMAL_INTEREST", p.normalInterest().won());
		addIfNonZero(e, "LATE_INTEREST", p.lateInterest().won());
		addIfNonZero(e, "COST", p.cost().won());
		addIfNonZero(e, "FEE", p.fee().won());
		addIfNonZero(e, "PRE_ACQ_INTEREST", p.preAcqInterest().won());
		addIfNonZero(e, "SUSPENSE", p.suspense().won());
	}

	private static void addIfNonZero(LedgerEntry e, String component, long amount) {
		if (amount != 0) {
			e.allocations.add(new LedgerAllocation(null, component, amount));
		}
	}

	/** 역분개: 원 거래의 금액을 부호만 바꿔 남기고 reversalOfId로 가리킨다 */
	LedgerEntry reversal(Long correctionId, LocalDate businessDate, OffsetDateTime now, String actor) {
		LedgerEntry r = new LedgerEntry();
		r.accountId = accountId;
		r.type = "REVERSAL";
		r.valueDate = valueDate;
		r.businessDate = businessDate;
		r.postedAt = now;
		r.sourceRef = sourceRef;
		r.amount = -amount;
		r.principal = -principal;
		r.normalInterest = -normalInterest;
		r.lateInterest = -lateInterest;
		r.cost = -cost;
		r.fee = -fee;
		r.preAcqInterest = -preAcqInterest;
		r.suspense = -suspense;
		r.refund = -refund;
		r.normalAccrued = -normalAccrued;
		r.lateAccrued = -lateAccrued;
		r.principalBalanceAfter = principalBalanceAfter;
		r.paymentId = paymentId;
		r.correctionId = correctionId;
		r.reversalOfId = id;
		r.createdAt = now;
		r.createdBy = actor;
		for (LedgerAllocation a : allocations) {
			r.allocations.add(new LedgerAllocation(a.installmentSeq(), a.component(), -a.amount()));
		}
		return r;
	}

	/** 비교용: 엔진 Posting과 같은 모양으로 */
	Posting toPosting() {
		List<Posting.InstallmentAllocation> allocs = allocations.stream()
				.filter(a -> a.installmentSeq() != null)
				.map(a -> new Posting.InstallmentAllocation(a.installmentSeq(), Money.won(a.amount()))).toList();
		return new Posting(0, valueDate, dev.arche.servicing.engine.PostingType.valueOf(type), sourceRef,
				Money.won(amount), Money.won(principal), Money.won(normalInterest), Money.won(lateInterest),
				Money.won(cost), Money.won(fee), Money.won(preAcqInterest), Money.won(suspense), Money.won(refund),
				Money.won(normalAccrued), Money.won(lateAccrued), Money.won(principalBalanceAfter), allocs);
	}

	String key() {
		return type + "|" + sourceRef + "|" + valueDate;
	}

	Long getId() {
		return id;
	}

	String type() {
		return type;
	}

	LocalDate valueDate() {
		return valueDate;
	}

	LocalDate businessDate() {
		return businessDate;
	}

	OffsetDateTime postedAt() {
		return postedAt;
	}

	String sourceRef() {
		return sourceRef;
	}

	long amount() {
		return amount;
	}

	long principal() {
		return principal;
	}

	long normalInterest() {
		return normalInterest;
	}

	long lateInterest() {
		return lateInterest;
	}

	long cost() {
		return cost;
	}

	long fee() {
		return fee;
	}

	long preAcqInterest() {
		return preAcqInterest;
	}

	long suspense() {
		return suspense;
	}

	long refund() {
		return refund;
	}

	long normalAccrued() {
		return normalAccrued;
	}

	long lateAccrued() {
		return lateAccrued;
	}

	long principalBalanceAfter() {
		return principalBalanceAfter;
	}

	Long paymentId() {
		return paymentId;
	}

	Long correctionId() {
		return correctionId;
	}

	Long reversalOfId() {
		return reversalOfId;
	}

	List<LedgerAllocation> allocations() {
		return allocations;
	}
}
