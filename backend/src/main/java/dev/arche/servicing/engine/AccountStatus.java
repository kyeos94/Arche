package dev.arche.servicing.engine;

/** 계약 상환 상태 (guide 16.3). CHARGED_OFF·SOLD는 R39·R40 */
public enum AccountStatus {
	NORMAL, DELINQUENT, ACCELERATED, WORKOUT, CHARGED_OFF, SOLD, PAID_OFF
}
