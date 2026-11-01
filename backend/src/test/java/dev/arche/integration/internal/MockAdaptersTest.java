package dev.arche.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.arche.common.BusinessException;
import dev.arche.integration.CreditBureauPort;
import dev.arche.integration.IdentityVerificationPort;
import dev.arche.integration.LoanPlatformPort;
import dev.arche.integration.StoragePort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MockAdaptersTest {

	@ParameterizedTest(name = "끝자리 {0} → 등급 {1}, 연체 {2}")
	@CsvSource({ "900101-1234561, 1, false", "9001011234568, 8, false", "900101-1234569, 9, true",
			"900101-1234560, 10, true" })
	void 신용조회_Mock은_식별번호_끝자리로_등급과_연체를_정한다(String id, int grade, boolean delinquent) {
		CreditBureauPort.Report r = MockCreditBureauAdapter.evaluate(id);
		assertThat(r.grade()).isEqualTo(grade);
		assertThat(r.currentlyDelinquent()).isEqualTo(delinquent);
		assertThat(r.totalDebt()).isEqualTo(r.openLoanCount() * 3_000_000L);
	}

	@Test
	void 신용조회_요청은_toString에_식별번호_원문을_남기지_않는다() {
		assertThat(new CreditBureauPort.Inquiry("900101-1234567", "심사").toString()).doesNotContain("234567");
	}

	static final class MutableClock extends Clock {
		Instant now = Instant.parse("2026-11-01T01:00:00Z");

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	@Test
	void 본인인증_Mock은_성공한_거래만_한_번_결과를_준다() {
		MockIdentityVerificationAdapter adapter = new MockIdentityVerificationAdapter(new MutableClock());
		IdentityVerificationPort.Session s = adapter.start("/landing/abc");
		assertThat(s.authUrl()).isEqualTo("/mock-identity/" + s.transactionId());
		assertThat(adapter.result(s.transactionId())).as("완료 전").isEmpty();

		IdentityVerificationPort.Session s2 = adapter.start("/landing/abc");
		String redirect = adapter.complete(s2.transactionId(), true, "김고객", LocalDate.of(1990, 1, 1));
		assertThat(redirect).isEqualTo("/landing/abc?tx=" + s2.transactionId());
		var identity = adapter.result(s2.transactionId());
		assertThat(identity).isPresent();
		assertThat(identity.get().name()).isEqualTo("김고객");
		assertThat(identity.get().connectingInfo()).startsWith("MOCKCI-");
		assertThat(adapter.result(s2.transactionId())).as("재사용 불가").isEmpty();

		IdentityVerificationPort.Session s3 = adapter.start("/landing/abc");
		adapter.complete(s3.transactionId(), false, null, null);
		assertThat(adapter.result(s3.transactionId())).as("실패 선택").isEmpty();
	}

	@Test
	void 본인인증_Mock은_만료된_거래와_외부_리다이렉트를_거부한다() {
		MutableClock clock = new MutableClock();
		MockIdentityVerificationAdapter adapter = new MockIdentityVerificationAdapter(clock);
		assertThatThrownBy(() -> adapter.start("https://evil.example/x")).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> adapter.start("//evil.example/x")).isInstanceOf(BusinessException.class);

		IdentityVerificationPort.Session s = adapter.start("/landing/abc");
		clock.now = clock.now.plus(MockIdentityVerificationAdapter.TTL).plus(Duration.ofSeconds(1));
		assertThatThrownBy(() -> adapter.complete(s.transactionId(), true, "김고객", LocalDate.of(1990, 1, 1)))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void 로컬_디스크_저장소는_무작위_키로_저장하고_경로_조작을_막는다(@TempDir Path dir) {
		LocalDiskStorageAdapter storage = new LocalDiskStorageAdapter(dir.toString());
		byte[] content = "서류".getBytes(StandardCharsets.UTF_8);
		StoragePort.StoredObject o = storage.store("application-documents", content);
		assertThat(o.key()).startsWith("application-documents/");
		assertThat(o.size()).isEqualTo(content.length);
		assertThat(o.sha256()).hasSize(64);
		assertThat(storage.load(o.key())).isEqualTo(content);

		assertThatThrownBy(() -> storage.load("../../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> storage.store("../x", content)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> storage.load("application-documents/00000000-0000-0000-0000-000000000000"))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void 플랫폼_Mock은_API_키로_인증하고_FAIL_참조는_전달_실패로_흉내낸다() {
		MockLoanPlatformAdapter p = new MockLoanPlatformAdapter("MOCKPLAT", "k", new MutableClock());
		assertThat(p.authenticate("k")).isTrue();
		assertThat(p.authenticate("x")).isFalse();
		assertThat(p.authenticate(null)).isFalse();
		assertThat(p.notifyResult(new LoanPlatformPort.ResultNotice("R1", "A1", "APPROVED", 1L, null, null))
				.delivered()).isTrue();
		assertThat(p.notifyResult(new LoanPlatformPort.ResultNotice("FAIL-1", "A2", "REJECTED", null, null, "X"))
				.delivered()).isFalse();
		assertThat(p.recent()).hasSize(2);
	}
}
