package dev.arche.integration;

import dev.arche.common.Masking;

/**
 * 팩스 발송 포트 (guide 11.1 FaxPort). 실제 기관 전문은 담지 않고 Mock 어댑터만 구현한다 (CLAUDE.md).
 */
public interface FaxPort {

	record FaxRequest(String faxNumber, String title, String body) {

		@Override
		public String toString() {
			return "FaxRequest[" + Masking.phone(faxNumber) + ", " + title + "]";
		}
	}

	/**
	 * @param accepted 기관이 송신을 마쳤는지 (false면 resultCode가 실패 사유)
	 * @param fileRef  발송 문서 보관 위치 (Mock은 로컬 파일 이름)
	 */
	record FaxResult(String providerId, boolean accepted, String resultCode, String fileRef) {
	}

	FaxResult send(FaxRequest request);
}
