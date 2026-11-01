package dev.arche.customer;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 고객 공개 API. 고객 식별번호(주민등록번호)는 암호문 + 블라인드 인덱스로만 저장하고, 원문 조회는 권한·사유·접근 기록 필수.
 */
public interface Customers {

	record NewCustomer(String name, String residentNo, LocalDate birthDate, String phone, String address) {

		@Override
		public String toString() {
			return "NewCustomer[name=" + name + "]";
		}
	}

	/** 같은 식별번호의 고객이 있으면 그 고객을 돌려준다 (블라인드 인덱스 검색) */
	CustomerView registerOrFind(NewCustomer command);

	CustomerView get(long id);

	Optional<CustomerView> findByResidentNo(String residentNo);

	/** 통합 검색 방법 */
	enum SearchKind {
		RESIDENT_NO, PHONE, NAME
	}

	/**
	 * 고객 검색. 주민등록번호(13자리)·휴대폰번호(01로 시작하는 10~11자리)는 블라인드 인덱스 일치, 그 외 2자 이상은 이름 부분 일치.
	 * 해당하지 않으면 빈 목록. 최대 50명
	 */
	List<CustomerView> search(String query);

	/** 입력값이 어떤 검색인지 (주민등록번호·휴대폰번호가 아니면 이름) */
	static SearchKind kindOf(String query) {
		String digits = query.replaceAll("[^0-9]", "");
		boolean onlyDigitsAndDashes = query.trim().matches("[0-9 -]+");
		if (onlyDigitsAndDashes && digits.length() == 13) {
			return SearchKind.RESIDENT_NO;
		}
		if (onlyDigitsAndDashes && digits.startsWith("01") && (digits.length() == 10 || digits.length() == 11)) {
			return SearchKind.PHONE;
		}
		return SearchKind.NAME;
	}

	/**
	 * 신용조회 기관에 보낼 식별번호 원문 (시스템 간 전송용, 화면 표시 금지). 호출자가 유효한 신용조회 동의를 확인한 뒤
	 * 그 동의 참조와 함께 부른다 (guide 11.2). 사용 사실은 접근 기록에 남긴다
	 */
	String residentNoForCreditInquiry(long id, String consentRef);

	/** 식별번호 원문 조회 (고객 식별번호 원문 조회 권한 + 사유, 접근 기록) */
	String revealResidentNo(long id, String reason);
}
