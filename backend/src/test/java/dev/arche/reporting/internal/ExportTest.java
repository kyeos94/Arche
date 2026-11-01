package dev.arche.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 엑셀 마스킹·작성 (R68 ②) */
class ExportTest {

	@ParameterizedTest
	@CsvSource({ "홍길동,홍*동", "김철,김*", "이,*", "남궁민수,남**수" })
	void 이름은_가운데를_가린다(String name, String masked) {
		assertThat(ExportController.maskName(name)).isEqualTo(masked);
	}

	@Test
	void 충당금_시트를_쓴다() throws Exception {
		var dto = new ReportController.ProvisionDto("2026-10", LocalDate.of(2026, 10, 31), true, 2, 3_000_000, 70_000,
				List.of(new ReportController.ProvisionRowDto("CURRENT", "정상", 1, 1_000_000, new BigDecimal("1.00"),
						10_000),
						new ReportController.ProvisionRowDto("D1_30", "1~30일", 1, 2_000_000, new BigDecimal("3.00"),
								60_000)));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (XlsxWriter w = new XlsxWriter()) {
			ReportExports.provision(dto).write(w);
			w.write(out);
		}
		try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
			Sheet s = wb.getSheet("대손충당금");
			assertThat(s.getRow(0).getCell(0).getStringCellValue()).contains("2026-10");
			assertThat(s.getRow(2).getCell(0).getStringCellValue()).isEqualTo("정상");
			assertThat((long) s.getRow(3).getCell(4).getNumericCellValue()).isEqualTo(60_000);
			assertThat((long) s.getRow(4).getCell(4).getNumericCellValue()).isEqualTo(70_000);
		}
	}
}
