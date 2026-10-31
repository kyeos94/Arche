package dev.arche.integration;

import java.time.LocalDate;

/**
 * 가상계좌 입금 통지 포트 (guide 11.1). 실제 기관 전문 형식은 담지 않고, Mock 어댑터만 구현한다 (CLAUDE.md).
 */
public interface VirtualAccountPort {

	/** 기관에서 받은 입금 통지를 표준 형태로 정규화한 것 */
	record DepositNotice(String bankTxId, String virtualAccountNo, long amount, LocalDate bankDate,
			String depositorName) {
	}

	/** 서명 검증. 실패하면 false */
	boolean verify(String payload, String signature);
}
