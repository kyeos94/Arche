package dev.arche.integration;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * 대출 비교 플랫폼 연계 포트 (guide 2.1, 11.1 LoanPlatformPort). 플랫폼마다 어댑터를 하나씩 두는 전략 패턴이다.
 * 한도조회·신청은 플랫폼이 우리 수신 API를 부르고(origination), 결과 통보는 이 포트로 우리가 플랫폼에 보낸다.
 * 실제 플랫폼 이름·전문 형식은 담지 않고 Mock 어댑터만 구현한다.
 */
public interface LoanPlatformPort {

	/** 결과 통보 (APPROVED / REJECTED / WITHDRAWN) */
	record ResultNotice(String platformRef, String applicationNo, String result, @Nullable Long approvedAmount,
			@Nullable BigDecimal approvedRate, @Nullable String reasonCode) {
	}

	record Delivery(boolean delivered, @Nullable String receiptId) {
	}

	/** 이 어댑터가 담당하는 플랫폼 코드 */
	String platformCode();

	/** 수신 API 호출 인증 (플랫폼별 API 키) */
	boolean authenticate(@Nullable String apiKey);

	Delivery notifyResult(ResultNotice notice);
}
