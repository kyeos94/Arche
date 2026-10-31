package dev.arche.staff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 정책서 9.1 역할별 권한표를 그대로 옮긴 표. 열 순서: 심사역, 심사팀장, 채권담당, 채권팀장, 출납, 본부장, 시스템관리자.
 */
class PermissionMatrixTest {

	private static final List<Role> COLUMNS = List.of(Role.UNDERWRITER, Role.UNDERWRITING_LEAD, Role.COLLECTOR,
			Role.COLLECTION_LEAD, Role.TELLER, Role.HEAD, Role.SYSTEM_ADMIN);

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', textBlock = """
			APPLICATION_REVIEW         | O O - - - O -
			APPLICATION_APPROVE_SMALL  | O O - - - O -
			APPLICATION_APPROVE_LARGE  | - O - - - O -
			LOAN_READ                  | - - O O O O O
			CUSTOMER_ID_REVEAL         | - O - O - O -
			PAYMENT_REGISTER           | - - O O O O -
			WITHDRAWAL_CREATE          | - - - - O - -
			WITHDRAWAL_APPROVE_SMALL   | - - - O - O -
			WITHDRAWAL_APPROVE_LARGE   | - - - O - O -
			TERM_CHANGE                | - - - O - O -
			WORKOUT_LATE_INTEREST      | - - O O - O -
			WORKOUT_NORMAL_INTEREST    | - - - O - O -
			WORKOUT_PRINCIPAL          | - - - - - O -
			CHARGE_OFF_SALE_CONFIRM    | - - - - - O -
			EXCEL_DOWNLOAD             | - O - O O O -
			ADMIN                      | - - - - - - O
			CLOSED_TX_CANCEL           | - - - - - - O
			LOAN_CREATE                | - - - - - O -
			""")
	void 정책서_9_1_권한표와_같다(Permission permission, String row) {
		List<String> marks = Arrays.asList(row.trim().split("\\s+"));
		for (int i = 0; i < COLUMNS.size(); i++) {
			assertThat(permission.grantedTo(COLUMNS.get(i))).as(permission + " / " + COLUMNS.get(i))
					.isEqualTo(marks.get(i).equals("O"));
		}
	}
}
