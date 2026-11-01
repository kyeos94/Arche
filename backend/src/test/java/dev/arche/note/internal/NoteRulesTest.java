package dev.arche.note.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 쪽지 제목·본문 검사 (R74) */
class NoteRulesTest {

	@Test
	void 길이와_공백() {
		assertThat(NoteService.text("  안녕하세요 ", 100, "제목")).isEqualTo("안녕하세요");
		assertThatThrownBy(() -> NoteService.text("   ", 100, "제목")).hasMessageContaining("1~100자");
		assertThatThrownBy(() -> NoteService.text("가".repeat(101), 100, "제목")).hasMessageContaining("1~100자");
	}

	@Test
	void 주민등록번호_형식은_거절하고_계약번호_가상계좌_전화번호는_허용() {
		for (String bad : new String[] { "900101-1234567", "9001011234567", "번호 900101 - 2234567 확인" }) {
			assertThatThrownBy(() -> NoteService.text(bad, 2000, "본문")).hasMessageContaining("주민등록번호");
		}
		for (String ok : new String[] { "L00001007", "가상계좌 99900000001", "010-1234-5678", "금액 12,345,678원",
				"2026-11-01", "AR2026110100001" }) {
			assertThat(NoteService.text(ok, 2000, "본문")).isEqualTo(ok);
		}
	}
}
