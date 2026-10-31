package dev.arche.servicing.engine;

/** 계약 상환 상태 (guide 16.3 중 MVP 범위) */
public enum AccountStatus {
	NORMAL, DELINQUENT, ACCELERATED, PAID_OFF
}
