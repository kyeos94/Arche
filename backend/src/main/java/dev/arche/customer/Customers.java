package dev.arche.customer;

import java.time.LocalDate;
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

	/** 식별번호 원문 조회 (고객 식별번호 원문 조회 권한 + 사유, 접근 기록) */
	String revealResidentNo(long id, String reason);
}
