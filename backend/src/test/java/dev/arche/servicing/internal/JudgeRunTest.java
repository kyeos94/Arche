package dev.arche.servicing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arche.common.BusinessException;
import dev.arche.servicing.LoanAccounts.JudgeFailure;
import dev.arche.servicing.LoanAccounts.JudgeReport;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 일마감 판정의 계약 단위 실패 격리 (R76). 트랜잭션 롤백은 통합 테스트(DailyClosingIT)에서 확인한다.
 */
class JudgeRunTest {

	@Test
	void R76_3개_계약_중_1개의_재생이_실패해도_나머지_2개는_판정되고_실패_1건이_남는다() {
		List<Long> judged = new ArrayList<>();
		JudgeReport report = JudgeRun.run(List.of(1L, 2L, 3L), id -> {
			if (id == 2L) {
				throw new IllegalStateException("재생 입력 오류: 이벤트 EV-9(PAYMENT)의 날짜 2026-05-01이 실행(양수)일 2026-05-10보다 이릅니다");
			}
			judged.add(id);
			return new JudgeRun.Judged(id == 3L ? 1 : 0, id == 3L);
		});

		assertThat(judged).containsExactly(1L, 3L);
		assertThat(report.accounts()).isEqualTo(2);
		assertThat(report.newNotices()).isEqualTo(1);
		assertThat(report.accelerations()).isEqualTo(1);
		assertThat(report.failures()).containsExactly(new JudgeFailure(2L, JudgeFailure.Kind.SYSTEM,
				"IllegalStateException", "재생 입력 오류: 이벤트 EV-9(PAYMENT)의 날짜 2026-05-01이 실행(양수)일 2026-05-10보다 이릅니다"));
	}

	@Test
	void R76_업무_규칙_위반은_오류_코드로_그_밖의_예외는_예외_이름으로_구분해_기록한다() {
		JudgeReport report = JudgeRun.run(List.of(1L, 2L), id -> {
			if (id == 1L) {
				throw new BusinessException("ACCOUNT_NOT_FOUND", "계약이 없습니다: 1");
			}
			throw new NullPointerException();
		});

		assertThat(report.accounts()).isZero();
		assertThat(report.failures()).extracting(JudgeFailure::kind, JudgeFailure::code, JudgeFailure::message)
				.containsExactly(
						org.assertj.core.groups.Tuple.tuple(JudgeFailure.Kind.BUSINESS, "ACCOUNT_NOT_FOUND",
								"계약이 없습니다: 1"),
						org.assertj.core.groups.Tuple.tuple(JudgeFailure.Kind.SYSTEM, "NullPointerException",
								"NullPointerException"));
	}

	@Test
	void R76_실패_메시지는_첫_줄_200자로_줄인다() {
		String longLine = "가".repeat(300);
		String summary = JudgeRun.summary(new IllegalStateException(longLine + "\n두 번째 줄"));
		assertThat(summary).hasSize(JudgeRun.MESSAGE_MAX).endsWith("…");
		assertThat(JudgeRun.summary(new IllegalStateException("첫 줄\n둘째 줄"))).isEqualTo("첫 줄");
	}

	@Test
	void R76_실행일보다_이른_재생_입력은_조용히_빠지지_않고_오류가_된다() {
		var start = java.time.LocalDate.parse("2026-05-10");
		var early = AccountEvent.payment(1L, 7L, start.minusDays(1), dev.arche.common.Money.won(100_000));
		var onStart = AccountEvent.payment(1L, 8L, start, dev.arche.common.Money.won(100_000));

		org.assertj.core.api.Assertions
				.assertThatThrownBy(() -> AccountReplayer.requireOnOrAfterStart(early, start))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("2026-05-09")
				.hasMessageContaining("2026-05-10");
		AccountReplayer.requireOnOrAfterStart(onStart, start);
	}

	@Test
	void R76_실패가_없으면_빈_목록() {
		JudgeReport report = JudgeRun.run(List.of(1L), id -> new JudgeRun.Judged(0, false));
		assertThat(report.accounts()).isEqualTo(1);
		assertThat(report.failures()).isEmpty();
	}
}
