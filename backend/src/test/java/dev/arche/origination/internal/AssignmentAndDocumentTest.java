package dev.arche.origination.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.arche.common.BusinessException;
import dev.arche.origination.internal.AssignmentStrategy.Candidate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** R55 배정 전략, R54 서류 */
class AssignmentAndDocumentTest {

	static OffsetDateTime at(String t) {
		return OffsetDateTime.parse("2026-11-01T" + t + ":00+09:00");
	}

	@Test
	void 처리_건수_균등은_미처리가_적은_사람_동률이면_마지막_배정이_오래된_순() {
		var s = AssignmentStrategy.of("least-loaded");
		var a = new Candidate(1, "가", 3, at("09:00"));
		var b = new Candidate(2, "나", 1, at("11:00"));
		var c = new Candidate(3, "다", 1, at("10:00"));
		var d = new Candidate(4, "라", 1, null);
		assertThat(s.choose(List.of(a, b, c)).orElseThrow().staffId()).isEqualTo(3);
		assertThat(s.choose(List.of(a, b, c, d)).orElseThrow().staffId()).as("배정 이력 없음이 가장 오래된 것")
				.isEqualTo(4);
		assertThat(s.choose(List.of())).isEmpty();
	}

	@Test
	void 라운드로빈은_마지막으로_배정받은_사람의_다음_사람() {
		var s = AssignmentStrategy.of("round-robin");
		var a = new Candidate(1, "가", 0, at("09:00"));
		var b = new Candidate(2, "나", 5, at("11:00"));
		var c = new Candidate(3, "다", 0, at("10:00"));
		assertThat(s.choose(List.of(a, b, c)).orElseThrow().staffId()).isEqualTo(3);
		var c2 = new Candidate(3, "다", 0, at("12:00"));
		assertThat(s.choose(List.of(a, b, c2)).orElseThrow().staffId()).as("끝이면 처음으로").isEqualTo(1);
		assertThat(s.choose(List.of(new Candidate(5, "마", 0, null), new Candidate(4, "바", 0, null))).orElseThrow()
				.staffId()).as("이력이 없으면 ID 순 첫 사람").isEqualTo(4);
	}

	@Test
	void 알_수_없는_전략은_기동_시_실패한다() {
		assertThatThrownBy(() -> AssignmentStrategy.of("random")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 서류는_허용_형식과_파일_첫_바이트가_맞아야_받는다() {
		DocumentService.validateFile("application/pdf", "%PDF-1.7 ...".getBytes());
		DocumentService.validateFile("image/png", new byte[] { (byte) 0x89, 'P', 'N', 'G', 1 });
		assertThatThrownBy(() -> DocumentService.validateFile("application/pdf", "MZ...".getBytes()))
				.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> DocumentService.validateFile("text/html", "<html>".getBytes()))
				.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> DocumentService.validateFile("image/jpeg", new byte[0]))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void 파일_이름은_경로를_떼고_보관한다() {
		assertThat(DocumentService.safeName("C:\\Users\\x\\신분증.pdf")).isEqualTo("신분증.pdf");
		assertThat(DocumentService.safeName("../../etc/passwd")).isEqualTo("passwd");
		assertThat(DocumentService.safeName(null)).isEqualTo("file");
	}

	@Test
	void 서류_상태는_접수_후에만_확인_보완요청할_수_있고_확인_후에는_바꿀_수_없다() {
		var d = new ApplicationDocument(1L, DocumentType.ID_CARD, true);
		var now = at("10:00");
		assertThatThrownBy(() -> d.verify("uw", now)).isInstanceOf(BusinessException.class);
		d.receivePaper("원본 대조", now);
		assertThatThrownBy(() -> d.reject("uw", " ", now)).as("보완 사유 필수").isInstanceOf(BusinessException.class);
		d.reject("uw", "흐림", now);
		assertThat(d.status()).isEqualTo(ApplicationDocument.Status.REJECTED);
		d.receiveFile(ApplicationDocument.Via.UPLOAD, "k/1", "a.pdf", "application/pdf", 10, "h", now);
		d.verify("uw", now);
		assertThat(d.status()).isEqualTo(ApplicationDocument.Status.VERIFIED);
		assertThatThrownBy(() -> d.receivePaper("다시", now)).isInstanceOf(BusinessException.class);
	}
}
