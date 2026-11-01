package dev.arche.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arche.common.Money;
import dev.arche.integration.CreditReportingPort;
import dev.arche.integration.PostalPort;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Mock 어댑터의 순수 계산 부분 (DB 없이) */
class MockAdaptersTest {

	@Test
	void 차량_시세는_기준가에_연식별_잔존율을_곱해_절사한다() {
		var v = MockCollateralValuationAdapter.vehiclePrice("중형", 2023, LocalDate.of(2026, 11, 1)).orElseThrow();
		// 32,000,000 × (1 − 3 × 0.1) = 22,400,000
		assertThat(v.price()).isEqualTo(Money.won(22_400_000));
		var old = MockCollateralValuationAdapter.vehiclePrice("경차", 2000, LocalDate.of(2026, 11, 1)).orElseThrow();
		assertThat(old.price()).isEqualTo(Money.won(3_000_000)); // 최저 잔존율 20%
		assertThat(MockCollateralValuationAdapter.vehiclePrice("우주선", 2020, LocalDate.of(2026, 11, 1))).isEmpty();
	}

	@Test
	void 부동산_시세는_가장_구체적인_지역의_단가를_쓴다() {
		var v = MockCollateralValuationAdapter
				.realEstatePrice("가상시 중앙구 1번길", new BigDecimal("84.95"), LocalDate.of(2026, 11, 1)).orElseThrow();
		assertThat(v.price()).isEqualTo(Money.won(764_550_000)); // 9,000,000 × 84.95
		var other = MockCollateralValuationAdapter
				.realEstatePrice("어딘가 2번길", new BigDecimal("10.333"), LocalDate.of(2026, 11, 1)).orElseThrow();
		assertThat(other.price()).isEqualTo(Money.won(30_999_000));
	}

	@Test
	void 등기_배달_상태는_접수_후_경과일로_진행하고_같은_입력이면_같다() {
		LocalDate accepted = LocalDate.of(2026, 11, 2);
		assertThat(MockPostalAdapter.progress("RGABC", accepted, accepted).status())
				.isEqualTo(PostalPort.DeliveryStatus.ACCEPTED);
		assertThat(MockPostalAdapter.progress("RGABC", accepted, accepted.plusDays(1)).status())
				.isEqualTo(PostalPort.DeliveryStatus.IN_TRANSIT);
		var done = MockPostalAdapter.progress("RGABC", accepted, accepted.plusDays(5));
		assertThat(done.status()).isIn(PostalPort.DeliveryStatus.DELIVERED, PostalPort.DeliveryStatus.RETURNED);
		assertThat(done.deliveredOn()).isBetween(accepted.plusDays(2), accepted.plusDays(3));
		assertThat(MockPostalAdapter.progress("RGABC", accepted, accepted.plusDays(5))).isEqualTo(done);
	}

	@Test
	void 신용정보_보고_파일은_120바이트_고정길이이고_결과_파일에_오류를_섞는다() {
		MockCreditReportingAdapter adapter = new MockCreditReportingAdapter(null, null);
		LocalDate base = LocalDate.of(2026, 11, 1);
		List<CreditReportingPort.ReportItem> items = List.of(
				new CreditReportingPort.ReportItem(1, CreditReportingPort.RecordType.OPEN, "L00001001", "C00001001",
						"홍길동", base, 10_000_000, 0),
				new CreditReportingPort.ReportItem(2, CreditReportingPort.RecordType.DELINQUENCY, "L00001002",
						"C00001002", "", base, 5_000_000, 95),
				new CreditReportingPort.ReportItem(3, CreditReportingPort.RecordType.CLOSE, "L00001003", "C00001003",
						"김철수", base, 0, 0));
		byte[] file = adapter.encode(base, items);
		assertThat(file).hasSize((120 + 2) * 5);
		List<byte[]> lines = CreditReportLayout.lines(file);
		assertThat(new String(lines.get(1), CreditReportLayout.CHARSET)).startsWith("D000000101L00001001   C00001001   홍길동");
		assertThat(new String(lines.get(4), CreditReportLayout.CHARSET)).startsWith("T0000003000000000015000000");

		List<byte[]> result = CreditReportLayout.lines(adapter.result(file));
		assertThat(result).hasSize(5);
		assertThat(new String(result.get(1), CreditReportLayout.CHARSET)).startsWith("R00000010000");
		assertThat(new String(result.get(2), CreditReportLayout.CHARSET)).startsWith("R0000002E101");
		assertThat(new String(result.get(3), CreditReportLayout.CHARSET)).startsWith("R0000003E201");
	}
}
