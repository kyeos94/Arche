package dev.arche.document.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.arche.common.InterestRate;
import dev.arche.common.Money;
import dev.arche.servicing.LoanAccounts.DocumentView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** 양식 치환 문자열과 인쇄 대상 판정 (R72) */
class DocumentRulesTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 11, 2);

	private static DocumentView view(String status, LocalDate delinquentSince, long overdue, String noticeStatus,
			LocalDate acceleratedOn) {
		return view(status, delinquentSince, overdue, noticeStatus, acceleratedOn, false);
	}

	private static DocumentView view(String status, LocalDate delinquentSince, long overdue, String noticeStatus,
			LocalDate acceleratedOn, boolean acquired) {
		return new DocumentView(1, "L00000001", 10, acquired ? "N1" : "P1", acquired, status, LocalDate.of(2026, 1, 10), Money.won(10_000_000),
				InterestRate.of("19.9"), Money.won(9_000_000), Money.won(700_000), Money.won(150_000),
				Money.won(3_210), Money.ZERO, Money.ZERO, Money.won(overdue), Money.won(9_200_000), delinquentSince,
				delinquentSince == null ? 0 : 20, 1, delinquentSince, null, acceleratedOn, noticeStatus,
				LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 14),
				LocalDate.of(2026, 10, 28), "99900000001", 5L, TODAY);
	}

	@Test
	void 치환_문자열을_값으로_바꾸고_HTML을_이스케이프하고_없는_값은_대시() {
		String html = DocumentPlaceholders.render("<p>{{고객명}} 님 {{ 계약번호 }} / {{상실일}}</p>",
				Map.of("고객명", "<b>홍&길동</b>", "계약번호", "L1"));
		assertThat(html).isEqualTo("<p>&lt;b&gt;홍&amp;길동&lt;/b&gt; 님 L1 / -</p>");
	}

	@Test
	void 모르는_치환_문자열은_거절() {
		assertThatThrownBy(() -> DocumentPlaceholders.validate("{{고객명}} {{주민등록번호}}"))
				.hasMessageContaining("주민등록번호");
		DocumentPlaceholders.validate("{{고객명}} {{완제금액}} .a { color: red }");
	}

	@Test
	void 기본_양식_두_개는_목록에_있는_치환_문자열만_쓴다() throws Exception {
		String sql = Files.readString(Path.of("src/main/resources/db/migration/V20__print_templates.sql"));
		Matcher m = Pattern.compile("\\{\\{\\s*([^{}\\s]+)\\s*}}").matcher(sql);
		int count = 0;
		while (m.find()) {
			assertThat(DocumentPlaceholders.CATALOG).containsKey(m.group(1));
			count++;
		}
		assertThat(count).isGreaterThan(30);
		// 주민등록번호 같은 식별번호는 양식에 넣을 수 없다
		assertThat(DocumentPlaceholders.CATALOG.keySet()).noneMatch(k -> k.contains("주민"));
	}

	@Test
	void 금액_날짜_형식() {
		Map<String, String> v = DocumentPlaceholders.values(view("DELINQUENT", LocalDate.of(2026, 10, 10), 853_210,
				null, null), "홍길동", "가상시 1", "개인신용 분할", "박채권",
				new DocumentPlaceholders.Company("예시대부(가상)", "가상시", "0000", "가상-1"), "P1-001",
				LocalDate.of(2026, 11, 9));
		assertThat(v.get("청구금액")).isEqualTo("853,210");
		assertThat(v.get("납부기한")).isEqualTo("2026년 11월 9일");
		assertThat(v.get("약정이율")).isEqualTo("19.9%");
		assertThat(v.get("입금계좌")).isEqualTo("가상계좌 99900000001");
	}

	@Test
	void 독촉장은_연체나_상실_계약만() {
		assertThat(PrintDocumentService.notTarget("DEMAND", view("NORMAL", null, 0, null, null)))
				.contains("연체 중이 아닙니다");
		assertThat(PrintDocumentService.notTarget("DEMAND", view("DELINQUENT", LocalDate.of(2026, 10, 10), 853_210, null,
				null))).isEmpty();
		assertThat(PrintDocumentService.notTarget("DEMAND", view("ACCELERATED", null, 9_200_000, "EXECUTED",
				LocalDate.of(2026, 10, 28)))).isEmpty();
		assertThat(PrintDocumentService.notTarget("DEMAND", view("PAID_OFF", null, 0, null, null)))
				.contains("완제된 계약입니다");
		assertThat(PrintDocumentService.notTarget("DEMAND", view("SOLD", LocalDate.of(2026, 10, 10), 1, null, null)))
				.hasValueSatisfying(r -> assertThat(r).contains("매각"));
	}

	@Test
	void 통보서는_통지가_진행_중이거나_상실된_계약만() {
		assertThat(PrintDocumentService.notTarget("ACCELERATION", view("DELINQUENT", LocalDate.of(2026, 10, 10), 1, null,
				null))).hasValueSatisfying(r -> assertThat(r).contains("통지 대상이 아닙니다"));
		assertThat(PrintDocumentService.notTarget("ACCELERATION", view("DELINQUENT", LocalDate.of(2026, 10, 10), 1,
				"PENDING", null))).isEmpty();
		assertThat(PrintDocumentService.notTarget("ACCELERATION", view("DELINQUENT", LocalDate.of(2026, 10, 10), 1,
				"CANCELLED", null))).isPresent();
		assertThat(PrintDocumentService.notTarget("ACCELERATION", view("CHARGED_OFF", null, 1, null,
				LocalDate.of(2026, 8, 6)))).isEmpty();
	}

	@Test
	void 매입채권은_통보서_대상이_아니고_독촉장의_연체일은_상실일부터() {
		DocumentView npl = view("ACCELERATED", null, 3_595_890, null, LocalDate.of(2026, 4, 15), true);
		assertThat(PrintDocumentService.notTarget("ACCELERATION", npl))
				.hasValueSatisfying(r -> assertThat(r).contains("매입채권"));
		assertThat(PrintDocumentService.notTarget("DEMAND", npl)).isEmpty();
		Map<String, String> v = DocumentPlaceholders.values(npl, "a", "b", "c", "d",
				new DocumentPlaceholders.Company("n", "a", "p", "r"), "1", TODAY);
		assertThat(v.get("연체시작일")).isEqualTo("2026년 4월 15일");
		assertThat(v.get("연체일수")).isEqualTo("201");
	}
}
