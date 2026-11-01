package dev.arche.treasury.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.common.BusinessException;
import dev.arche.common.Money;
import dev.arche.common.jpa.NumberGenerator;
import dev.arche.customer.Customers;
import dev.arche.integration.TransferPort;
import dev.arche.payment.UnmatchedRefunds;
import dev.arche.servicing.LoanAccounts;
import dev.arche.servicing.RefundPayouts;
import dev.arche.staff.CurrentStaff;
import dev.arche.staff.StaffDirectory;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

interface WithdrawalRepository extends JpaRepository<Withdrawal, Long> {

	List<Withdrawal> findTop200ByOrderByIdDesc();

	List<Withdrawal> findByStatusInOrderByIdDesc(List<Withdrawal.Status> statuses);

	List<Withdrawal> findByStatusInOrderById(List<Withdrawal.Status> statuses);
}

/**
 * 출금 (guide 6.4, R62~R64). 작성 시 예금주를 확인하고 금액을 예약하며, 반려·취소·송금 실패면 예약을 되돌린다.
 * 송금은 최종 승인 트랜잭션이 커밋된 뒤 따로 요청하고, 결과는 다른 트랜잭션에 기록한다 (외부 호출 중 DB 락을 잡지 않음).
 * 응답이 불확실하면 성공으로 보지 않고 UNKNOWN으로 두었다가 결과 조회로 확정한다. 재송금은 하지 않는다.
 */
@Service
class WithdrawalService {

	private static final Logger log = LoggerFactory.getLogger(WithdrawalService.class);
	/** SENDING으로 이만큼 머물면(서버 중단 등) 결과 조회 대상 */
	static final Duration STALE_SENDING = Duration.ofMinutes(5);

	record CreateCommand(Withdrawal.Type type, @Nullable Long accountId, @Nullable Long unmatchedId, long amount,
			String bankName, String accountNo, String holderName, String reason) {

		@Override
		public String toString() {
			return "CreateCommand[" + type + ", " + amount + "]";
		}
	}

	private final WithdrawalRepository withdrawals;
	private final UnmatchedRefunds unmatched;
	private final RefundPayouts refunds;
	private final LoanAccounts accounts;
	private final Customers customers;
	private final TransferPort transfer;
	private final StaffDirectory staff;
	private final BusinessDates businessDates;
	private final NumberGenerator numbers;
	private final Clock clock;
	private final TransactionTemplate tx;

	WithdrawalService(WithdrawalRepository withdrawals, UnmatchedRefunds unmatched, RefundPayouts refunds,
			LoanAccounts accounts, Customers customers, TransferPort transfer, StaffDirectory staff,
			BusinessDates businessDates, NumberGenerator numbers, Clock clock, PlatformTransactionManager txm) {
		this.withdrawals = withdrawals;
		this.unmatched = unmatched;
		this.refunds = refunds;
		this.accounts = accounts;
		this.customers = customers;
		this.transfer = transfer;
		this.staff = staff;
		this.businessDates = businessDates;
		this.numbers = numbers;
		this.clock = clock;
		this.tx = new TransactionTemplate(txm);
	}

	// ------------------------------------------------------------------ 작성 (출납)

	Withdrawal create(CreateCommand c) {
		if (c.reason() == null || c.reason().isBlank()) {
			throw new BusinessException("REASON_REQUIRED", "출금 사유를 입력하세요");
		}
		if (c.amount() <= 0) {
			throw new BusinessException("AMOUNT_INVALID", "금액은 0보다 커야 합니다");
		}
		String holder = c.holderName().strip();
		// 예금주 실명 확인 (guide 6.4)은 외부 호출이라 트랜잭션 밖에서 먼저
		TransferPort.HolderCheck check = transfer.verifyHolder(c.bankName(), c.accountNo(), holder);
		if (!check.matched()) {
			throw new BusinessException("HOLDER_MISMATCH", "예금주 확인 결과가 일치하지 않습니다 (" + check.resultCode() + ")");
		}
		CurrentStaff me = staff.current();
		return tx.execute(s -> {
			String no = numbers.next("withdrawal_no_seq", "W");
			Money amount = Money.won(c.amount());
			Withdrawal w;
			if (c.type() == Withdrawal.Type.UNMATCHED_REFUND) {
				if (c.unmatchedId() == null) {
					throw new BusinessException("UNMATCHED_REQUIRED", "반환할 미확인 입금을 고르세요");
				}
				UnmatchedRefunds.UnmatchedInfo u = unmatched.get(c.unmatchedId());
				// 미확인 입금은 전액을 입금자 본인 계좌로만 반환 (R62)
				if (u.amount() != c.amount()) {
					throw new BusinessException("AMOUNT_INVALID", "미확인 입금은 전액(" + Money.won(u.amount()) + ")만 반환합니다");
				}
				if (u.depositorName() != null && !u.depositorName().isBlank() && !u.depositorName().strip().equals(holder)) {
					throw new BusinessException("HOLDER_MISMATCH", "미확인 입금은 입금자 본인 계좌로만 반환합니다");
				}
				unmatched.reserve(c.unmatchedId());
				w = new Withdrawal(no, c.type(), null, c.unmatchedId(), amount, c.bankName(), c.accountNo(), holder,
						c.reason(), businessDates.today(), me.id());
			} else {
				if (c.accountId() == null) {
					throw new BusinessException("ACCOUNT_REQUIRED", "반환할 계약을 고르세요");
				}
				LoanAccounts.MessagingView v = accounts.messagingView(c.accountId());
				if (v.paidOffOn() == null) {
					throw new BusinessException("REFUND_NOT_ALLOWED",
							"계약 중 가수금은 반환하지 않습니다. 완제 후 과입금만 반환합니다 (R62)");
				}
				if (!customers.get(v.customerId()).name().equals(holder)) {
					throw new BusinessException("HOLDER_MISMATCH", "과입금은 채무자 본인 계좌로만 반환합니다");
				}
				refunds.reserve(c.accountId(), no, amount);
				w = new Withdrawal(no, c.type(), c.accountId(), null, amount, c.bankName(), c.accountNo(), holder,
						c.reason(), businessDates.today(), me.id());
			}
			return withdrawals.save(w);
		});
	}

	// ------------------------------------------------------------------ 승인·반려·취소

	/** 승인. 최종 승인이면 커밋 후 바로 송금을 요청한다 */
	Withdrawal approve(long id) {
		CurrentStaff me = staff.current();
		ApprovalPolicy.Step step = tx.execute(s -> {
			Withdrawal w = find(id);
			ApprovalPolicy.Step st = ApprovalPolicy.next(w.twoStep(), w.status(), w.requestedBy(),
					w.firstApprovedBy(), me.id(), me.role());
			OffsetDateTime now = OffsetDateTime.now(clock);
			if (st == ApprovalPolicy.Step.FIRST) {
				w.firstApprove(me.id(), now);
			} else {
				w.finalApprove(me.id(), now);
			}
			return st;
		});
		if (step == ApprovalPolicy.Step.FINAL) {
			send(id);
		}
		return tx.execute(s -> find(id));
	}

	Withdrawal reject(long id, String reason) {
		if (reason == null || reason.isBlank()) {
			throw new BusinessException("REASON_REQUIRED", "반려 사유를 입력하세요");
		}
		CurrentStaff me = staff.current();
		return tx.execute(s -> {
			Withdrawal w = find(id);
			ApprovalPolicy.checkReject(w.twoStep(), w.status(), w.requestedBy(), w.firstApprovedBy(), me.id(),
					me.role());
			w.reject(me.id(), reason);
			releaseReservation(w, "반려");
			return w;
		});
	}

	/** 작성자 본인이 승인 전에 취소 */
	Withdrawal cancel(long id) {
		CurrentStaff me = staff.current();
		return tx.execute(s -> {
			Withdrawal w = find(id);
			if (w.requestedBy() != me.id()) {
				throw new BusinessException("FORBIDDEN", "작성자만 취소할 수 있습니다");
			}
			if (!w.status().open()) {
				throw new BusinessException("WITHDRAWAL_CLOSED", "승인이 끝난 출금은 취소할 수 없습니다");
			}
			w.cancel();
			releaseReservation(w, "작성 취소");
			return w;
		});
	}

	// ------------------------------------------------------------------ 송금·결과 조회 (R64)

	void send(long id) {
		TransferPort.TransferRequest request = tx.execute(s -> {
			Withdrawal w = find(id);
			return w.status() == Withdrawal.Status.SENDING && w.sentAt() == null ? w.transferRequest() : null;
		});
		if (request == null) {
			return;
		}
		TransferPort.TransferResult result;
		try {
			result = transfer.transfer(request);
		} catch (RuntimeException e) {
			// 예외도 응답 불명으로 본다: 성공으로 가정하지 않고 결과 조회로 확정
			log.warn("송금 요청 예외 {}: {}", request.idempotencyKey(), e.getClass().getSimpleName());
			result = new TransferPort.TransferResult(TransferPort.Status.UNKNOWN, null, "EXCEPTION", "송금 요청 중 오류");
		}
		record(id, result, false);
	}

	/** 결과 조회 (응답 불명 건). 다시 송금하지 않는다 */
	Withdrawal inquire(long id) {
		TransferPort.TransferRequest request = tx.execute(s -> {
			Withdrawal w = find(id);
			if (w.status() != Withdrawal.Status.UNKNOWN) {
				throw new BusinessException("WITHDRAWAL_CLOSED", "결과 조회 대상이 아닙니다");
			}
			return w.transferRequest();
		});
		record(id, transfer.inquire(request), true);
		return tx.execute(s -> find(id));
	}

	/**
	 * 1분마다: UNKNOWN은 결과 조회, 최종 승인 후 송금 요청을 마치지 못한 채 오래 머문 SENDING(서버 중단 등)은
	 * 같은 멱등 키로 다시 요청한다(기관이 같은 키를 한 번만 처리하므로 이중 송금이 되지 않음).
	 */
	@Scheduled(fixedDelayString = "${arche.transfer.inquiry-delay:PT1M}")
	@SchedulerLock(name = "transfer-inquiry", lockAtMostFor = "PT5M")
	void scheduledInquiry() {
		OffsetDateTime staleBefore = OffsetDateTime.now(clock).minus(STALE_SENDING);
		List<Withdrawal> targets = tx.execute(s -> withdrawals
				.findByStatusInOrderById(List.of(Withdrawal.Status.UNKNOWN, Withdrawal.Status.SENDING)));
		for (Withdrawal w : targets) {
			try {
				if (w.status() == Withdrawal.Status.UNKNOWN) {
					inquire(w.getId());
				} else if (w.finalApprovedAt() != null && w.finalApprovedAt().isBefore(staleBefore)) {
					send(w.getId());
				}
			} catch (RuntimeException e) {
				log.error("송금 결과 조회 실패 {}", w.withdrawalNo(), e);
			}
		}
	}

	private void record(long id, TransferPort.TransferResult result, boolean inquiry) {
		tx.executeWithoutResult(s -> {
			Withdrawal w = find(id);
			if (w.status() != Withdrawal.Status.SENDING && w.status() != Withdrawal.Status.UNKNOWN) {
				return;
			}
			w.result(result, OffsetDateTime.now(clock), inquiry);
			if (w.status() == Withdrawal.Status.FAILED) {
				releaseReservation(w, "송금 실패");
			}
		});
	}

	private void releaseReservation(Withdrawal w, String why) {
		if (w.type() == Withdrawal.Type.UNMATCHED_REFUND) {
			unmatched.release(w.unmatchedId());
		} else {
			refunds.release(w.accountId(), w.withdrawalNo(), w.amount(), why);
		}
	}

	Withdrawal find(long id) {
		return withdrawals.findById(id)
				.orElseThrow(() -> new BusinessException("WITHDRAWAL_NOT_FOUND", "출금이 없습니다"));
	}
}
