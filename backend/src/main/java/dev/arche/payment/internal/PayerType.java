package dev.arche.payment.internal;

/** 입금자 구분 (R49). 본인이 아닌 입금은 대위변제 기록을 남기고 소멸시효 중단으로 세지 않는다 (R50) */
enum PayerType {
	CUSTOMER, GUARANTOR, THIRD_PARTY
}
