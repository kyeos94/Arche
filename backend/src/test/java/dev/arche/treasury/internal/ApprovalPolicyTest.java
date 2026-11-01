package dev.arche.treasury.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.arche.common.BusinessException;
import dev.arche.common.Money;
import dev.arche.staff.Role;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** 출금 승인 단계 (정책서 7장·9.1, R63) */
class ApprovalPolicyTest {

	private static final long MAKER = 1;

	@Test
	void 천만원_이하는_채권팀장이나_본부장_한_명이면_끝난다() {
		assertThat(ApprovalPolicy.next(false, Withdrawal.Status.REQUESTED, MAKER, null, 2, Role.COLLECTION_LEAD))
				.isEqualTo(ApprovalPolicy.Step.FINAL);
		assertThat(ApprovalPolicy.next(false, Withdrawal.Status.REQUESTED, MAKER, null, 2, Role.HEAD))
				.isEqualTo(ApprovalPolicy.Step.FINAL);
		assertThatThrownBy(() -> ApprovalPolicy.next(false, Withdrawal.Status.REQUESTED, MAKER, null, 2, Role.TELLER))
				.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> ApprovalPolicy.next(false, Withdrawal.Status.REQUESTED, MAKER, null, 2, Role.COLLECTOR))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void 천만원_초과는_채권팀장_다음_본부장_순서이고_같은_사람이_두_번_못한다() {
		assertThat(ApprovalPolicy.next(true, Withdrawal.Status.REQUESTED, MAKER, null, 2, Role.COLLECTION_LEAD))
				.isEqualTo(ApprovalPolicy.Step.FIRST);
		assertThatThrownBy(() -> ApprovalPolicy.next(true, Withdrawal.Status.REQUESTED, MAKER, null, 3, Role.HEAD))
				.hasMessageContaining("1단계");
		assertThatThrownBy(
				() -> ApprovalPolicy.next(true, Withdrawal.Status.FIRST_APPROVED, MAKER, 2L, 4, Role.COLLECTION_LEAD))
				.hasMessageContaining("2단계");
		assertThat(ApprovalPolicy.next(true, Withdrawal.Status.FIRST_APPROVED, MAKER, 2L, 3, Role.HEAD))
				.isEqualTo(ApprovalPolicy.Step.FINAL);
		// 데모 전용 역할도 사람 분리는 그대로
		assertThatThrownBy(
				() -> ApprovalPolicy.next(true, Withdrawal.Status.FIRST_APPROVED, MAKER, 5L, 5, Role.DEMO_ALL))
				.hasMessageContaining("1단계 승인자");
	}

	@Test
	void 작성자는_승인할_수_없고_끝난_출금은_승인할_수_없다() {
		assertThatThrownBy(() -> ApprovalPolicy.next(false, Withdrawal.Status.REQUESTED, MAKER, null, MAKER, Role.HEAD))
				.hasMessageContaining("작성자");
		assertThatThrownBy(() -> ApprovalPolicy.next(false, Withdrawal.Status.SENDING, MAKER, null, 2, Role.HEAD))
				.hasMessageContaining("상태");
	}

	@Test
	void 천만원은_1단계_천만1원부터_2단계() {
		assertThat(withdrawal(10_000_000).twoStep()).isFalse();
		assertThat(withdrawal(10_000_001).twoStep()).isTrue();
		assertThat(withdrawal(10_000_000).transferRequest().toString()).doesNotContain("1234567890")
				.contains("******7890");
	}

	private static Withdrawal withdrawal(long amount) {
		return new Withdrawal("W00001001", Withdrawal.Type.OVERPAYMENT_REFUND, 1L, null, Money.won(amount), "가상은행",
				"1234567890", "홍길동", "반환", LocalDate.of(2026, 11, 1), MAKER);
	}
}
