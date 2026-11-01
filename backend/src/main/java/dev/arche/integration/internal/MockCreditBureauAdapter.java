package dev.arche.integration.internal;

import dev.arche.integration.CreditBureauPort;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Mock 신용조회 어댑터 (guide 11.1: 식별값 끝자리로 등급·연체 여부 결정). 가상 체계이며 실제 기관 기준과 무관하다.
 * <ul>
 * <li>끝자리 1~8 → 등급 1~8, 연체 없음</li>
 * <li>끝자리 9 → 등급 9, 현재 연체 있음</li>
 * <li>끝자리 0 → 등급 10, 현재 연체 있음</li>
 * </ul>
 * 보유 대출 건수 = 끝자리 % 4, 잔액 = 건수 × 300만원. 식별번호 평문은 로그에 남기지 않는다.
 */
@Component
class MockCreditBureauAdapter implements CreditBureauPort {

	private static final Logger log = LoggerFactory.getLogger(MockCreditBureauAdapter.class);

	@Override
	public Report inquire(Inquiry inquiry) {
		Report report = evaluate(inquiry.identifier());
		log.info("[Mock 신용조회] {} → 등급 {}, 연체 {}", report.inquiryRef(), report.grade(), report.currentlyDelinquent());
		return report;
	}

	static Report evaluate(String identifier) {
		String digits = identifier == null ? "" : identifier.replaceAll("[^0-9]", "");
		if (digits.isEmpty()) {
			throw new IllegalArgumentException("식별번호가 없습니다");
		}
		int last = digits.charAt(digits.length() - 1) - '0';
		int grade = last == 0 ? 10 : last;
		int loans = last % 4;
		return new Report("MOCKCB-" + UUID.randomUUID(), grade, grade >= 9, loans, loans * 3_000_000L);
	}
}
