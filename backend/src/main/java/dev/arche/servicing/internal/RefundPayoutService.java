package dev.arche.servicing.internal;

import dev.arche.common.BusinessException;
import dev.arche.common.Money;
import dev.arche.servicing.RefundPayouts;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.hibernate.annotations.Immutable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 과입금 반환 예약(+)·해제(−) 기록. DB 트리거로 UPDATE·DELETE 금지 */
@Entity
@Immutable
@Table(name = "refund_payout")
class RefundPayout {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	private long accountId;
	private String withdrawalNo;
	private long amount;
	private String memo;
	private OffsetDateTime createdAt;
	private String createdBy;

	protected RefundPayout() {
	}

	RefundPayout(long accountId, String withdrawalNo, long amount, String memo, OffsetDateTime createdAt,
			String createdBy) {
		this.accountId = accountId;
		this.withdrawalNo = withdrawalNo;
		this.amount = amount;
		this.memo = memo;
		this.createdAt = createdAt;
		this.createdBy = createdBy;
	}
}

interface RefundPayoutRepository extends JpaRepository<RefundPayout, Long> {

	@Query("select coalesce(sum(r.amount), 0) from RefundPayout r where r.accountId = :accountId")
	long reserved(@Param("accountId") long accountId);

	@Query("select coalesce(sum(r.amount), 0) from RefundPayout r where r.accountId = :accountId"
			+ " and r.withdrawalNo = :no")
	long reservedFor(@Param("accountId") long accountId, @Param("no") String withdrawalNo);
}

interface RefundPendingAccountRepository extends JpaRepository<LoanAccount, Long> {

	@Query("select a from LoanAccount a where a.refundPending > 0 order by a.id")
	List<LoanAccount> findWithRefundPending();
}

@Service
class RefundPayoutService implements RefundPayouts {

	private final LoanAccountRepository accounts;
	private final RefundPendingAccountRepository pendingAccounts;
	private final RefundPayoutRepository payouts;
	private final Clock clock;

	RefundPayoutService(LoanAccountRepository accounts, RefundPendingAccountRepository pendingAccounts,
			RefundPayoutRepository payouts, Clock clock) {
		this.accounts = accounts;
		this.pendingAccounts = pendingAccounts;
		this.payouts = payouts;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public Refundable refundable(long accountId) {
		LoanAccount a = accounts.findById(accountId)
				.orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "계약이 없습니다"));
		return view(a);
	}

	@Override
	@Transactional(readOnly = true)
	public List<Refundable> withRefundPending() {
		return pendingAccounts.findWithRefundPending().stream().map(this::view).filter(r -> r.available() > 0)
				.toList();
	}

	@Override
	@Transactional
	public void reserve(long accountId, String withdrawalNo, Money amount) {
		LoanAccount a = accounts.lockById(accountId)
				.orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "계약이 없습니다"));
		if (!amount.isPositive()) {
			throw new BusinessException("AMOUNT_INVALID", "반환 금액은 0보다 커야 합니다");
		}
		Refundable r = view(a);
		if (amount.won() > r.available()) {
			throw new BusinessException("REFUND_EXCEEDED",
					"반환 가능액(" + Money.won(r.available()) + ")을 넘습니다");
		}
		payouts.save(new RefundPayout(accountId, withdrawalNo, amount.won(), "출금 예약", OffsetDateTime.now(clock),
				actor()));
	}

	@Override
	@Transactional
	public void release(long accountId, String withdrawalNo, Money amount, String memo) {
		accounts.lockById(accountId).orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "계약이 없습니다"));
		long reserved = payouts.reservedFor(accountId, withdrawalNo);
		if (reserved < amount.won()) {
			throw new BusinessException("REFUND_NOT_RESERVED", "해제할 예약이 없습니다");
		}
		payouts.save(new RefundPayout(accountId, withdrawalNo, -amount.won(), memo, OffsetDateTime.now(clock),
				actor()));
	}

	private Refundable view(LoanAccount a) {
		long reserved = payouts.reserved(a.getId());
		return new Refundable(a.getId(), a.accountNo(), a.refundPending(), reserved,
				Math.max(0, a.refundPending() - reserved));
	}

	private static String actor() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		return auth == null ? "system" : auth.getName();
	}
}
