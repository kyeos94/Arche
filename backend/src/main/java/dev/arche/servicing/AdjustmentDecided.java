package dev.arche.servicing;

/**
 * 조정·취소 결재 요청이 승인 또는 반려됨 (R42~R44). 커밋 뒤 쪽지 모듈이 받아 요청자에게 알린다 (R74).
 */
public record AdjustmentDecided(long requestId, String requestNo, long accountId, String accountNo, String typeLabel,
		long requestedBy, boolean approved, String decidedByName) {
}
