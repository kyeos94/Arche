package dev.arche.integration.internal.fixedlength;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.arche.integration.internal.fixedlength.FixedLengthRecord.Field;
import java.nio.charset.Charset;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FixedLengthMapperTest {

	private static final Charset MS949 = Charset.forName("MS949");
	private final FixedLengthMapper mapper = new FixedLengthMapper(MS949);

	@FixedLengthRecord(length = 30)
	record Sample(@Field(offset = 0, length = 1) String kind, @Field(offset = 1, length = 5) int seq,
			@Field(offset = 6, length = 7) String name, @Field(offset = 13, length = 8) LocalDate date,
			@Field(offset = 21, length = 6) long amount) {
	}

	@FixedLengthRecord(length = 10)
	record Overlap(@Field(offset = 0, length = 5) String a, @Field(offset = 4, length = 2) String b) {
	}

	@FixedLengthRecord(length = 4)
	record TooLong(@Field(offset = 0, length = 5) String a) {
	}

	@Test
	void 바이트_길이로_채우고_숫자는_0_문자는_공백으로_채운다() {
		byte[] bytes = mapper.encode(new Sample("D", 42, "홍길동", LocalDate.of(2026, 11, 1), 1234));
		assertThat(bytes).hasSize(30);
		// 홍길동 = 6바이트 + 공백 1, 끝 3바이트는 filler
		assertThat(new String(bytes, MS949)).isEqualTo("D00042홍길동 20261101001234   ");
	}

	@Test
	void 한글은_문자_경계에서_자르고_되읽으면_같은_값이다() {
		byte[] bytes = mapper.encode(new Sample("D", 1, "가나다라마", LocalDate.of(2026, 1, 2), 0));
		// 7바이트에 한글은 3글자(6바이트)까지만 들어간다. 4번째 글자를 반으로 자르지 않는다
		Sample back = mapper.decode(bytes, Sample.class);
		assertThat(back.name()).isEqualTo("가나다");
		assertThat(back.seq()).isEqualTo(1);
		assertThat(back.date()).isEqualTo(LocalDate.of(2026, 1, 2));
		assertThat(back.amount()).isZero();
	}

	@Test
	void 자리수를_넘는_금액이나_음수는_자르지_않고_오류() {
		assertThatThrownBy(() -> mapper.encode(new Sample("D", 1, "a", LocalDate.of(2026, 1, 1), 1_000_000)))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("자리");
		assertThatThrownBy(() -> mapper.encode(new Sample("D", -1, "a", LocalDate.of(2026, 1, 1), 1)))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("음수");
	}

	@Test
	void 레이아웃_오류는_처음_쓸_때_알려준다() {
		assertThatThrownBy(() -> mapper.encode(new Overlap("a", "b"))).hasMessageContaining("겹칩니다");
		assertThatThrownBy(() -> mapper.encode(new TooLong("a"))).hasMessageContaining("레코드 길이");
	}
}
