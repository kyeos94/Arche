package dev.arche.integration;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * 등기우편 발송·배달 조회 포트 (guide 11.1 PostalPort). 수신 주소는 어댑터가 고객 ID로 정한다 (개인정보를 호출자에게 노출하지 않음).
 * 실제 기관 전문·코드 체계는 담지 않고 Mock 어댑터만 구현한다.
 */
public interface PostalPort {

	record PostalRequest(long customerId, String title, String body) {
	}

	record Accepted(String trackingNo, String fileRef) {
	}

	enum DeliveryStatus {
		ACCEPTED, IN_TRANSIT, DELIVERED, RETURNED
	}

	record Tracking(String trackingNo, DeliveryStatus status, @Nullable LocalDate deliveredOn, String note) {
	}

	Accepted send(PostalRequest request);

	/** 배달 조회. today는 업무일자 (Mock은 접수 후 경과일로 상태를 진행시킨다) */
	Tracking track(String trackingNo, LocalDate acceptedOn, LocalDate today);
}
