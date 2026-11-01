package dev.arche.customer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SearchKindTest {

	@ParameterizedTest(name = "{0} → {1}")
	@CsvSource({
			"900101-1234567, RESIDENT_NO",
			"9001011234567, RESIDENT_NO",
			"010-1234-5678, PHONE",
			"01012345678, PHONE",
			"011-123-4567, PHONE",
			"홍길동, NAME",
			"L00001001, NAME",
			"00001001, NAME",
			"02-123-4567, NAME" })
	void 입력값_형식으로_검색_방법을_고른다(String query, Customers.SearchKind kind) {
		assertThat(Customers.kindOf(query)).isEqualTo(kind);
	}
}
