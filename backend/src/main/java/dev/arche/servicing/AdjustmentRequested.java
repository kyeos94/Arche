package dev.arche.servicing;

/**
 * 조정·취소 결재 요청이 들어옴 (R42~R44). 커밋 뒤 쪽지 모듈이 받아 결재할 수 있는 직원에게 알린다 (R74).
 */
public record AdjustmentRequested(long requestId, String requestNo, long accountId, String accountNo, String typeLabel,
		long requestedBy, String requestedByName) {
}
