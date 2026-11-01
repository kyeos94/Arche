package dev.arche.integration;

import dev.arche.common.Masking;

/**
 * 신용조회 포트 (guide 11.1 CreditBureauPort). 실제 기관 이름·전문 형식·등급 체계는 담지 않고 Mock 어댑터만 구현한다 (CLAUDE.md).
 * 호출 전에 유효한 신용조회 동의가 있는지 호출자가 검사한다 (guide 11.2, 정책서 R53).
 */
public interface CreditBureauPort {

	/**
	 * @param identifier 고객 식별번호 원문. 어댑터는 로그·toString에 평문을 남기지 않는다
	 * @param purpose    조회 목적 (예: "대출 심사")
	 */
	record Inquiry(String identifier, String purpose) {

		@Override
		public String toString() {
			return "Inquiry[identifier=" + Masking.residentNo(identifier) + ", purpose=" + purpose + "]";
		}
	}

	/**
	 * 조회 결과 (가상 체계). grade는 1(우량) ~ 10(불량).
	 *
	 * @param inquiryRef           기관 조회 번호
	 * @param currentlyDelinquent  현재 연체 여부 (정책서 7장 자동 거절)
	 * @param openLoanCount        보유 대출 건수
	 * @param totalDebt            보유 대출 잔액 합계(원)
	 */
	record Report(String inquiryRef, int grade, boolean currentlyDelinquent, int openLoanCount, long totalDebt) {
	}

	Report inquire(Inquiry inquiry);
}
