package dev.arche.payment.internal;

import dev.arche.common.BusinessException;
import dev.arche.payment.UnmatchedRefunds;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link UnmatchedRefunds} 구현. 상태 변경은 조건부 UPDATE(+ version 증가)로 해서, 같은 미확인 입금을 동시에
 * 매칭하는 요청은 낙관적 락 충돌로 실패한다.
 */
@Service
class UnmatchedRefundService implements UnmatchedRefunds {

	private final UnmatchedDepositRepository unmatched;
	private final PaymentRepository payments;
	private final EntityManager em;

	UnmatchedRefundService(UnmatchedDepositRepository unmatched, PaymentRepository payments, EntityManager em) {
		this.unmatched = unmatched;
		this.payments = payments;
		this.em = em;
	}

	@Override
	@Transactional(readOnly = true)
	public UnmatchedInfo get(long unmatchedId) {
		return info(unmatched.findById(unmatchedId)
				.orElseThrow(() -> new BusinessException("UNMATCHED_NOT_FOUND", "미확인 입금이 없습니다")));
	}

	@Override
	@Transactional(readOnly = true)
	public List<UnmatchedInfo> open() {
		return unmatched.findByStatusOrderByIdDesc(UnmatchedDeposit.Status.OPEN).stream().map(this::info).toList();
	}

	@Override
	@Transactional
	public void reserve(long unmatchedId) {
		if (move(unmatchedId, UnmatchedDeposit.Status.OPEN, UnmatchedDeposit.Status.REFUNDED) == 0) {
			throw new BusinessException("UNMATCHED_CLOSED", "이미 처리된 미확인 입금입니다");
		}
	}

	@Override
	@Transactional
	public void release(long unmatchedId) {
		if (move(unmatchedId, UnmatchedDeposit.Status.REFUNDED, UnmatchedDeposit.Status.OPEN) == 0) {
			throw new BusinessException("UNMATCHED_NOT_RESERVED", "반환 예약된 미확인 입금이 아닙니다");
		}
	}

	private int move(long id, UnmatchedDeposit.Status from, UnmatchedDeposit.Status to) {
		int n = em.createQuery("update UnmatchedDeposit u set u.status = :to, u.version = u.version + 1"
				+ " where u.id = :id and u.status = :from").setParameter("to", to).setParameter("from", from)
				.setParameter("id", id).executeUpdate();
		return n;
	}

	private UnmatchedInfo info(UnmatchedDeposit u) {
		Payment p = payments.findById(u.paymentId())
				.orElseThrow(() -> new BusinessException("PAYMENT_NOT_FOUND", "입금이 없습니다"));
		return new UnmatchedInfo(u.getId(), p.getId(), p.amount().won(), p.valueDate(), p.depositorName(),
				u.status().name());
	}
}
