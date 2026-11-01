package dev.arche.payment.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.arche.common.BusinessException;
import org.junit.jupiter.api.Test;

/** R49 입금자 구분 */
class PayerTest {

	@Test
	void 본인_입금은_이름을_받지_않는다() {
		assertThat(Payer.of(null, "아무개", "메모")).isSameAs(Payer.CUSTOMER);
		assertThat(Payer.of(PayerType.CUSTOMER, null, null).isCustomer()).isTrue();
	}

	@Test
	void 보증인_제3자는_이름이_필수다() {
		assertThatThrownBy(() -> Payer.of(PayerType.GUARANTOR, " ", null)).isInstanceOf(BusinessException.class);
		Payer p = Payer.of(PayerType.THIRD_PARTY, " 박제삼 ", " ");
		assertThat(p.name()).isEqualTo("박제삼");
		assertThat(p.ref()).isNull();
	}

	@Test
	void 이름은_마스킹하고_toString에_평문을_내지_않는다() {
		assertThat(Payer.mask("김보증")).isEqualTo("김*증");
		assertThat(Payer.mask("남궁보증인")).isEqualTo("남***인");
		assertThat(Payer.mask("이준")).isEqualTo("이*");
		assertThat(Payer.of(PayerType.GUARANTOR, "김보증", null).toString()).doesNotContain("김보증");
	}

	@Test
	void 법원_배당금_공탁금과_제3자_입금은_채무자_승인이_아니다() {
		assertThat(dev.arche.payment.Payments.Channel.AUCTION_DISTRIBUTION.court()).isTrue();
		assertThat(dev.arche.payment.Payments.Channel.COURT_DEPOSIT.court()).isTrue();
		assertThat(dev.arche.payment.Payments.Channel.BANK_TRANSFER.court()).isFalse();
	}
}
