package dev.arche.reporting.internal;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/**
 * 스트리밍 xlsx 작성 (Apache POI SXSSF, 메모리에는 최근 100행만, ADR 0009). 금액은 숫자 셀(#,##0)로 쓴다.
 */
final class XlsxWriter implements AutoCloseable {

	private final SXSSFWorkbook wb = new SXSSFWorkbook(100);
	private final CellStyle money;
	private final CellStyle bold;
	private Sheet sheet;
	private int rowIndex;

	XlsxWriter() {
		money = wb.createCellStyle();
		money.setDataFormat(wb.createDataFormat().getFormat("#,##0"));
		bold = wb.createCellStyle();
		Font f = wb.createFont();
		f.setBold(true);
		bold.setFont(f);
	}

	XlsxWriter sheet(String name) {
		sheet = wb.createSheet(name);
		rowIndex = 0;
		return this;
	}

	XlsxWriter title(String text) {
		Row r = sheet.createRow(rowIndex++);
		Cell c = r.createCell(0);
		c.setCellValue(text);
		c.setCellStyle(bold);
		return this;
	}

	XlsxWriter header(String... names) {
		Row r = sheet.createRow(rowIndex++);
		for (int i = 0; i < names.length; i++) {
			Cell c = r.createCell(i);
			c.setCellValue(names[i]);
			c.setCellStyle(bold);
		}
		return this;
	}

	XlsxWriter blank() {
		rowIndex++;
		return this;
	}

	XlsxWriter row(Object... values) {
		Row r = sheet.createRow(rowIndex++);
		for (int i = 0; i < values.length; i++) {
			Object v = values[i];
			if (v == null) {
				continue;
			}
			Cell c = r.createCell(i);
			switch (v) {
				case Long l -> {
					c.setCellValue(l);
					c.setCellStyle(money);
				}
				case Integer n -> c.setCellValue(n);
				// 비율은 소수 첫째 자리 문자열 그대로 (금액·비율에 double을 쓰지 않음)
				case BigDecimal b -> c.setCellValue(b.toPlainString());
				case LocalDate d -> c.setCellValue(d.toString());
				case Boolean b -> c.setCellValue(b ? "예" : "아니오");
				default -> c.setCellValue(v.toString());
			}
		}
		return this;
	}

	void write(OutputStream out) throws IOException {
		wb.write(out);
	}

	@Override
	public void close() {
		try {
			// SXSSF 임시 파일도 close에서 정리된다
			wb.close();
		} catch (IOException ignored) {
			// 임시 파일 정리 실패는 응답에 영향 없음
		}
	}
}
