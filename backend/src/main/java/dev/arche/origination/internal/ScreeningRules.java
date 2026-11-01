package dev.arche.origination.internal;

import dev.arche.integration.CreditBureauPort;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 사전심사 자동 거절 규칙 (정책서 7장 + R53). DB·Spring 없이 사실(fact)만 받아 판정하는 순수 함수.
 * 규칙 순서: 만 나이 → 중복 신청 → 최근 30일 거절 이력 → 내부 불량 이력 → 신용조회. 앞 규칙에서 거절이면 신용조회는 하지 않는다.
 */
final class ScreeningRules {

	static final int MIN_AGE = 19;
	/** 최근 거절 이력 판정 기간: 직전 30일(당일 포함) — 정책서 6장 "직전 7일(당일 포함)"과 같은 셈법 */
	static final int REJECTION_LOOKBACK_DAYS = 30;

	enum RuleCode {
		MIN_AGE("만 19세 미만"),
		DUPLICATE_APPLICATION("중복 신청"),
		RECENT_REJECTION("최근 30일 거절 이력"),
		INTERNAL_HISTORY("내부 불량 이력"),
		CREDIT_BUREAU("신용조회 현재 연체");

		final String label;

		RuleCode(String label) {
			this.label = label;
		}
	}

	enum Outcome {
		PASS, REJECT, SKIPPED
	}

	record Result(RuleCode rule, Outcome outcome, String detail) {
		boolean rejected() {
			return outcome == Outcome.REJECT;
		}
	}

	private ScreeningRules() {
	}

	static Result minAge(LocalDate birthDate, LocalDate today) {
		int age = Period.between(birthDate, today).getYears();
		return age >= MIN_AGE ? new Result(RuleCode.MIN_AGE, Outcome.PASS, "만 " + age + "세")
				: new Result(RuleCode.MIN_AGE, Outcome.REJECT, "만 " + age + "세 (만 19세 미만)");
	}

	/** @param activeOthers 같은 고객(식별번호 블라인드 인덱스로 동일인)의 진행 중인 다른 신청 번호 */
	static Result duplicate(List<String> activeOthers) {
		return activeOthers.isEmpty()
				? new Result(RuleCode.DUPLICATE_APPLICATION, Outcome.PASS, "진행 중인 다른 신청 없음")
				: new Result(RuleCode.DUPLICATE_APPLICATION, Outcome.REJECT,
						"진행 중인 신청 " + String.join(", ", activeOthers));
	}

	/** @param rejectedOn 같은 고객의 다른 신청이 거절된 업무일자들 */
	static Result recentRejection(List<LocalDate> rejectedOn, LocalDate today) {
		LocalDate from = today.minusDays(REJECTION_LOOKBACK_DAYS - 1L);
		return rejectedOn.stream().filter(d -> !d.isBefore(from) && !d.isAfter(today)).max(LocalDate::compareTo)
				.map(d -> new Result(RuleCode.RECENT_REJECTION, Outcome.REJECT, d + " 거절 (" + from + " 이후)"))
				.orElseGet(() -> new Result(RuleCode.RECENT_REJECTION, Outcome.PASS, from + " 이후 거절 이력 없음"));
	}

	/** @param adverseAccounts 불량 상태 계약 ("계약번호 상태") */
	static Result internalHistory(List<String> adverseAccounts) {
		return adverseAccounts.isEmpty()
				? new Result(RuleCode.INTERNAL_HISTORY, Outcome.PASS, "불량 상태 계약 없음")
				: new Result(RuleCode.INTERNAL_HISTORY, Outcome.REJECT, String.join(", ", adverseAccounts));
	}

	/**
	 * @param priorRejected 앞 규칙에서 이미 거절됐는지 (그러면 조회하지 않음)
	 * @param consented     유효한 신용조회 동의가 있는지 (없으면 조회하지 않음, guide 11.2)
	 * @param report        조회 결과 (조회하지 않았으면 null)
	 */
	static Result creditBureau(boolean priorRejected, boolean consented, CreditBureauPort.@Nullable Report report) {
		if (priorRejected) {
			return new Result(RuleCode.CREDIT_BUREAU, Outcome.SKIPPED, "앞 규칙에서 거절되어 조회하지 않음");
		}
		if (!consented || report == null) {
			return new Result(RuleCode.CREDIT_BUREAU, Outcome.SKIPPED, "유효한 신용조회 동의 없음 — 조회하지 않음");
		}
		String summary = "등급 " + report.grade() + ", 보유 대출 " + report.openLoanCount() + "건";
		return report.currentlyDelinquent()
				? new Result(RuleCode.CREDIT_BUREAU, Outcome.REJECT, summary + ", 현재 연체 있음")
				: new Result(RuleCode.CREDIT_BUREAU, Outcome.PASS, summary + ", 현재 연체 없음");
	}

	/** 조회 전 4개 규칙 */
	static List<Result> beforeCreditBureau(LocalDate birthDate, LocalDate today, List<String> activeOthers,
			List<LocalDate> rejectedOn, List<String> adverseAccounts) {
		return List.of(minAge(birthDate, today), duplicate(activeOthers), recentRejection(rejectedOn, today),
				internalHistory(adverseAccounts));
	}

	static boolean anyRejected(List<Result> results) {
		return results.stream().anyMatch(Result::rejected);
	}
}
