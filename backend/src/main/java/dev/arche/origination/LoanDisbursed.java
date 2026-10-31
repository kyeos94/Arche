package dev.arche.origination;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 대출 실행 이벤트 (guide 2.4). 실행 트랜잭션 안에서 Spring Modulith 이벤트 발행 기록(Outbox)에 저장되고,
 * 커밋 후 servicing이 받아 계약을 만든다. 신청 ID로 멱등 처리한다.
 */
public record LoanDisbursed(long applicationId, String applicationNo, long customerId, String productCode,
		long amount, BigDecimal rate, int termMonths, int graceMonths, int dueDay, LocalDate disbursedOn) {
}
