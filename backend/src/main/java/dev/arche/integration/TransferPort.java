package dev.arche.integration;

import dev.arche.common.Money;

/**
 * 송금(펌뱅킹) 포트 (guide 11.1 TransferPort, 6.4): 예금주 확인 → 송금 요청 → 결과 조회.
 * 응답이 불확실(타임아웃)하면 성공으로 가정하지 않고 UNKNOWN을 돌려준다. 같은 멱등 키의 요청은 한 번만 송금된다.
 * 실제 은행·기관 코드 체계는 담지 않고 Mock 어댑터만 구현한다.
 */
public interface TransferPort {

	record HolderCheck(boolean matched, String resultCode) {
	}

	record TransferRequest(String idempotencyKey, String bankName, String accountNo, String holderName,
			Money amount) {

		@Override
		public String toString() {
			return "TransferRequest[" + idempotencyKey + ", " + bankName + " " + maskAccount(accountNo) + ", "
					+ amount + "]";
		}
	}

	enum Status {
		SUCCEEDED, FAILED, UNKNOWN
	}

	record TransferResult(Status status, String providerTxId, String resultCode, String message) {
	}

	/** 예금주 실명 확인 (guide 6.4) */
	HolderCheck verifyHolder(String bankName, String accountNo, String holderName);

	TransferResult transfer(TransferRequest request);

	/** 결과 조회. 원 요청(멱등 키)으로 찾는다. 다시 송금하지 않는다 */
	TransferResult inquire(TransferRequest original);

	/** 계좌번호 마스킹: 끝 4자리만 남김 (1234567890 → ******7890) */
	static String maskAccount(String accountNo) {
		if (accountNo == null) {
			return null;
		}
		String digits = accountNo.replaceAll("[^0-9]", "");
		if (digits.length() <= 4) {
			return "****";
		}
		return "*".repeat(digits.length() - 4) + digits.substring(digits.length() - 4);
	}
}
