package dev.arche.origination.internal;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * 모든 접수 채널(직원 입력·중개업자 API·대출 비교 플랫폼)의 요청을 정규화한 명령 (guide 2.1).
 * 이후 로직(자동 거절·배정·심사)은 채널을 모른다. 채널마다 다른 것은 인증 방식과 동의 증빙뿐이다.
 *
 * @param intakeSource 플랫폼 코드 또는 중개업자 번호 (직원 입력은 null)
 * @param externalRef  채널 쪽 신청 번호. (intakeSource, externalRef)가 같으면 같은 신청으로 보고 다시 만들지 않는다
 * @param brokerId     중개 수수료 정산 대상 중개업자 (R56)
 * @param consent      접수와 함께 받은 신용조회 동의 (없으면 null)
 */
record ApplicationIntakeCommand(LoanApplication.Channel channel, @Nullable String intakeSource,
		@Nullable String externalRef, @Nullable Long brokerId, long customerId, String productCode,
		long requestedAmount, int termMonths, int graceMonths, int dueDay, @Nullable Consent consent) {

	record Consent(ApplicationConsent.Channel channel, LocalDate agreedOn, @Nullable String evidence) {
	}
}
