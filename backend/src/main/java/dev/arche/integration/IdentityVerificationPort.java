package dev.arche.integration;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 본인인증 포트 (guide 11.1 IdentityVerificationPort, 11.3 랜딩 페이지 흐름). 팝업/리다이렉트 후 결과를 받는다.
 * 실제 기관의 화면·전문·연계정보 형식은 담지 않고 Mock 어댑터(가짜 인증 화면)만 구현한다.
 */
public interface IdentityVerificationPort {

	/** 인증 시작: 고객을 보낼 인증 화면 주소 */
	record Session(String transactionId, String authUrl) {
	}

	/**
	 * 인증 성공 결과. connectingInfo는 기관이 주는 개인 고유 연계값(가상)으로, 저장할 때는 해시만 남긴다.
	 */
	record VerifiedIdentity(String transactionId, String name, LocalDate birthDate, String connectingInfo) {

		@Override
		public String toString() {
			return "VerifiedIdentity[tx=" + transactionId + "]";
		}
	}

	/**
	 * @param returnUrl 인증을 마친 뒤 돌아올 화면 주소 (거래번호가 {@code tx} 파라미터로 붙는다)
	 */
	Session start(String returnUrl);

	/**
	 * 인증 결과 조회. 성공한 거래만 값이 있고, 같은 거래는 한 번만 꺼낼 수 있다 (재사용 방지). 실패·미완료·만료면 empty
	 */
	Optional<VerifiedIdentity> result(String transactionId);
}
