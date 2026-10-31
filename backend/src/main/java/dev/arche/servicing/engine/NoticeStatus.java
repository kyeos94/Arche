package dev.arche.servicing.engine;

/** 기한이익상실 통지 상태 (guide 16.3). VOID는 재계산으로 사유가 없어진 통지 → 저장 시 REVOKED·CANCELLED */
public enum NoticeStatus {
	PENDING, CANCELLED, EXECUTED, VOID
}
