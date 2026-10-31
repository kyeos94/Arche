package dev.arche.payment.internal.bulk;

import dev.arche.common.BusinessException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * 엑셀 일괄 처리 골격 (guide 12.3): 업로드 → 행별 파싱·검증 → (승인 후) 행 단위 실행 → 실패 행 다운로드.
 * 유형별 구현은 열 정의, 파싱·검증, 실행만 작성한다.
 *
 * @param <T> 행 하나를 표현하는 타입
 */
abstract class AbstractBulkProcessor<T> {

	static final int MAX_ROWS = 10_000;

	record Parsed<T>(int rowNo, String payload, T value, String error) {
		boolean valid() {
			return error == null;
		}
	}

	abstract String type();

	abstract List<String> headers();

	/** 셀 값 → T. 형식 오류는 BusinessException */
	abstract T parse(List<String> cells);

	/** 존재 여부·중복·권한 등 업무 검증. 오류 메시지 또는 null */
	abstract String validate(T value, List<T> earlierRowsInFile);

	/** 한 행 실행 (호출자가 행마다 별도 트랜잭션). 성공 메시지 */
	abstract String execute(T value);

	abstract T fromPayload(String payload);

	abstract String toPayload(T value);

	List<Parsed<T>> read(InputStream in) {
		DataFormatter formatter = new DataFormatter();
		List<Parsed<T>> result = new ArrayList<>();
		List<T> earlier = new ArrayList<>();
		try (Workbook wb = new XSSFWorkbook(in)) {
			Sheet sheet = wb.getSheetAt(0);
			for (int r = 1; r <= sheet.getLastRowNum(); r++) {
				Row row = sheet.getRow(r);
				if (row == null || isBlank(row, formatter)) {
					continue;
				}
				if (result.size() >= MAX_ROWS) {
					throw new BusinessException("BULK_TOO_LARGE", "한 번에 " + MAX_ROWS + "행까지 처리합니다");
				}
				List<String> cells = new ArrayList<>();
				for (int c = 0; c < headers().size(); c++) {
					Cell cell = row.getCell(c);
					cells.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
				}
				String raw = String.join("|", cells);
				try {
					T value = parse(cells);
					String error = validate(value, earlier);
					earlier.add(value);
					result.add(new Parsed<>(r + 1, toPayload(value), value, error));
				} catch (BusinessException | IllegalArgumentException | java.time.DateTimeException e) {
					result.add(new Parsed<>(r + 1, raw, null, e.getMessage()));
				}
			}
		} catch (IOException e) {
			throw new BusinessException("BULK_FILE_INVALID", "엑셀(xlsx) 파일을 읽을 수 없습니다");
		}
		return result;
	}

	byte[] template() {
		return write(List.of());
	}

	/** 실패 행 다운로드: 원래 열 + 결과 메시지 */
	byte[] write(List<String[]> rows) {
		try (SXSSFWorkbook wb = new SXSSFWorkbook(100); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sheet = wb.createSheet(type());
			Row header = sheet.createRow(0);
			List<String> cols = new ArrayList<>(headers());
			if (!rows.isEmpty()) {
				cols.add("행 번호");
				cols.add("오류");
			}
			for (int i = 0; i < cols.size(); i++) {
				header.createCell(i).setCellValue(cols.get(i));
			}
			int r = 1;
			for (String[] values : rows) {
				Row row = sheet.createRow(r++);
				for (int i = 0; i < values.length; i++) {
					row.createCell(i).setCellValue(values[i]);
				}
			}
			wb.write(out);
			return out.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static boolean isBlank(Row row, DataFormatter f) {
		for (Cell c : row) {
			if (!f.formatCellValue(c).isBlank()) {
				return false;
			}
		}
		return true;
	}
}
