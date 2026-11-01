package dev.arche.servicing;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * 계약 목록 검색 조건 (ADR 0011). 목록 화면과 엑셀 다운로드가 같은 조건을 쓴다. 비어 있는 값은 조건 없음.
 *
 * @param keywordType ACCOUNT_NO(계약번호 부분 일치)·NAME(고객 성명 부분 일치)·RESIDENT_NO·PHONE(블라인드 인덱스 일치)·
 *                    AUTO(예전 검색창: 주민등록번호 형식이면 주민등록번호, 아니면 계약번호)
 * @param unassigned  true면 담당자가 없는 계약만 (assignedStaffId보다 우선)
 */
public record LoanFilter(@Nullable String status, @Nullable String productCode, @Nullable Long assignedStaffId,
		boolean unassigned, @Nullable Long principalMin, @Nullable Long principalMax, @Nullable Long payoffMin,
		@Nullable Long payoffMax, @Nullable Integer delinquencyMin, @Nullable Integer delinquencyMax,
		@Nullable LocalDate nextDueFrom, @Nullable LocalDate nextDueTo, @Nullable LocalDate disbursedFrom,
		@Nullable LocalDate disbursedTo, @Nullable String keywordType, @Nullable String keyword) {

	public static LoanFilter none() {
		return new LoanFilter(null, null, null, false, null, null, null, null, null, null, null, null, null, null, null,
				null);
	}

	/** 식별번호로 찾는 검색인지 (접근 기록 대상) */
	public boolean identifierSearch() {
		return keyword != null && !keyword.isBlank()
				&& ("RESIDENT_NO".equals(keywordType) || "PHONE".equals(keywordType)
						|| ("AUTO".equals(keywordType) && keyword.trim().matches("[0-9]{6}-?[0-9]{7}")));
	}

	@Override
	public String toString() {
		// 검색어는 주민등록번호·휴대폰일 수 있어 남기지 않는다
		return "LoanFilter[status=" + status + ", product=" + productCode + ", keywordType=" + keywordType + "]";
	}
}
