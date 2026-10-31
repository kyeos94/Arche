package dev.arche.integration;

/**
 * 문자 발송 포트 (guide 11.1 MessagingPort). 실제 기관 전문·발신 번호는 담지 않고 Mock 어댑터만 구현한다 (CLAUDE.md).
 * 수신 번호는 어댑터가 고객 ID로 조회한다 (개인정보를 호출자에게 노출하지 않음).
 */
public interface MessagingPort {

	record Sent(String providerId) {
	}

	Sent send(long customerId, String body);
}
