package dev.arche.servicing.internal;

import dev.arche.common.BusinessException;
import dev.arche.servicing.LoanAccounts.JudgeFailure;
import dev.arche.servicing.LoanAccounts.JudgeReport;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 일마감 ②~④ 판정 반복 (R24·R76). 계약마다 judgeOne을 부르고, 한 계약이 실패해도 그 계약만 실패 목록에 남기고 다음 계약을 계속한다.
 * <p>
 * judgeOne은 계약 하나를 자기 트랜잭션(REQUIRES_NEW)에서 재생하므로, 실패한 계약은 그 트랜잭션만 롤백되어 asOf가 실패 전
 * 그대로 남는다. 재생은 결정적이라(ADR 0002) 데이터를 고친 뒤 다음 마감(findIdsToJudge가 asOf가 판정일 이하인 계약을 다시
 * 고른다)이나 재판정 때 같은 입력으로 그날까지 따라잡고, 처음부터 실패하지 않았던 것과 같은 결과가 된다 (ADR 0012).
 */
final class JudgeRun {

	private static final Logger log = LoggerFactory.getLogger(JudgeRun.class);

	/** 실패 메시지 요약 최대 길이 (daily_closing_failure.message) */
	static final int MESSAGE_MAX = 200;

	/** 계약 하나의 판정 결과 */
	record Judged(int newNotices, boolean newlyAccelerated) {
	}

	private JudgeRun() {
	}

	static JudgeReport run(List<Long> accountIds, LongFunction<Judged> judgeOne) {
		int count = 0;
		int notices = 0;
		int accelerations = 0;
		List<JudgeFailure> failures = new ArrayList<>();
		for (long id : accountIds) {
			Judged j;
			try {
				j = judgeOne.apply(id);
			} catch (BusinessException e) {
				log.error("일마감 판정 실패 계약 id {}: [{}] {}", id, e.code(), e.getMessage());
				failures.add(new JudgeFailure(id, JudgeFailure.Kind.BUSINESS, e.code(), summary(e)));
				continue;
			} catch (RuntimeException e) {
				log.error("일마감 판정 실패 계약 id {}: 예상하지 못한 오류", id, e);
				failures.add(new JudgeFailure(id, JudgeFailure.Kind.SYSTEM, e.getClass().getSimpleName(), summary(e)));
				continue;
			}
			count++;
			notices += j.newNotices();
			accelerations += j.newlyAccelerated() ? 1 : 0;
		}
		return new JudgeReport(count, notices, accelerations, failures);
	}

	/** 메시지 첫 줄, 최대 200자. 메시지가 없으면 예외 이름 */
	static String summary(RuntimeException e) {
		String m = e.getMessage();
		if (m == null || m.isBlank()) {
			return e.getClass().getSimpleName();
		}
		String first = m.strip().lines().findFirst().orElse(m);
		return first.length() <= MESSAGE_MAX ? first : first.substring(0, MESSAGE_MAX - 1) + "…";
	}
}
