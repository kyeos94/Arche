package dev.arche.customer.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** V6 이전 고객의 휴대폰 블라인드 인덱스를 기동 시 채운다. 이미 채워졌으면 아무것도 하지 않는다 */
@Component
@Order(5)
class PhoneIndexBackfill implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(PhoneIndexBackfill.class);

	private final CustomerService customers;

	PhoneIndexBackfill(CustomerService customers) {
		this.customers = customers;
	}

	@Override
	public void run(ApplicationArguments args) {
		int filled = customers.backfillPhoneIndex();
		if (filled > 0) {
			log.info("휴대폰 블라인드 인덱스 {}건 채움", filled);
		}
	}
}
