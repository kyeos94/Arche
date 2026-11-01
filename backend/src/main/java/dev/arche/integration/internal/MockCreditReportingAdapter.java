package dev.arche.integration.internal;

import dev.arche.integration.CreditReportingPort;
import dev.arche.integration.internal.fixedlength.FixedLengthMapper;
import dev.arche.integration.internal.fixedlength.FixedLengthRecord;
import dev.arche.integration.internal.fixedlength.FixedLengthRecord.Field;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 신용정보 보고 파일 레이아웃 — 이 저장소가 자체 설계한 일반형 고정길이 120바이트(MS949), 레코드마다 CRLF.
 * 실제 기관의 형식·코드 체계가 아니다 (CLAUDE.md).
 *
 * <pre>
 * 보고 파일   H 헤더 / D 데이터 × n / T 트레일러
 * 결과 파일   H 헤더 / R 결과 × n / T 트레일러
 * 사유 코드   01 개설, 02 연체 등록, 03 연체 해제, 04 종료
 * 결과 코드   0000 정상, E101 필수 항목 누락, E201 계약 미등록(기관 측), E301 일자 오류
 * </pre>
 */
final class CreditReportLayout {

	static final Charset CHARSET = Charset.forName("MS949");
	static final int LENGTH = 120;
	static final String SENDER = "ARCHE001";

	private CreditReportLayout() {
	}

	@FixedLengthRecord(length = LENGTH)
	record Header(@Field(offset = 0, length = 1) String kind, @Field(offset = 1, length = 4) String fileType,
			@Field(offset = 5, length = 8) LocalDate baseDate, @Field(offset = 13, length = 8) String sender) {
	}

	@FixedLengthRecord(length = LENGTH)
	record Data(@Field(offset = 0, length = 1) String kind, @Field(offset = 1, length = 7) int seq,
			@Field(offset = 8, length = 2) String reason, @Field(offset = 10, length = 12) String accountNo,
			@Field(offset = 22, length = 12) String customerNo, @Field(offset = 34, length = 20) String name,
			@Field(offset = 54, length = 8) LocalDate eventDate, @Field(offset = 62, length = 15) long balance,
			@Field(offset = 77, length = 5) int delinquencyDays) {
	}

	@FixedLengthRecord(length = LENGTH)
	record Trailer(@Field(offset = 0, length = 1) String kind, @Field(offset = 1, length = 7) int count,
			@Field(offset = 8, length = 18) long totalBalance) {
	}

	@FixedLengthRecord(length = LENGTH)
	record Result(@Field(offset = 0, length = 1) String kind, @Field(offset = 1, length = 7) int seq,
			@Field(offset = 8, length = 4) String code, @Field(offset = 12, length = 40) String message) {
	}

	static String reasonCode(CreditReportingPort.RecordType t) {
		return switch (t) {
			case OPEN -> "01";
			case DELINQUENCY -> "02";
			case RELEASE -> "03";
			case CLOSE -> "04";
		};
	}

	static List<byte[]> lines(byte[] file) {
		List<byte[]> lines = new ArrayList<>();
		int step = LENGTH + 2;
		for (int i = 0; i + LENGTH <= file.length; i += step) {
			lines.add(Arrays.copyOfRange(file, i, i + LENGTH));
		}
		return lines;
	}
}

/**
 * Mock 신용정보 보고: 보고 파일을 로컬(outbound)에 저장, 전송하면 sent로 복사하고 결과 파일(inbound)을 임의로 만든다.
 * 결과 파일은 이름이 빈 레코드에 E101, 일련번호를 7로 나눈 나머지가 3인 레코드에 E201을 붙인다(오류 처리 확인용).
 */
@Component
class MockCreditReportingAdapter implements CreditReportingPort {

	static final String INSTITUTION = "가상 신용정보집중기관";
	private static final DateTimeFormatter NAME_DATE = DateTimeFormatter.BASIC_ISO_DATE;

	private final FixedLengthMapper mapper = new FixedLengthMapper(CreditReportLayout.CHARSET);
	private final MockFileStore files;
	private final CallLogger calls;

	MockCreditReportingAdapter(MockFileStore files, CallLogger calls) {
		this.files = files;
		this.calls = calls;
	}

	@Override
	public GeneratedFile generate(LocalDate baseDate, List<ReportItem> items) {
		byte[] content = encode(baseDate, items);
		String name = "CRPT_" + NAME_DATE.format(baseDate) + "_" + CreditReportLayout.SENDER + ".dat";
		files.write("credit-report/outbound", name, content);
		return new GeneratedFile(name, items.size(), content.length);
	}

	byte[] encode(LocalDate baseDate, List<ReportItem> items) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		line(out, new CreditReportLayout.Header("H", "CRPT", baseDate, CreditReportLayout.SENDER));
		long total = 0;
		for (ReportItem i : items) {
			line(out, new CreditReportLayout.Data("D", i.seq(), CreditReportLayout.reasonCode(i.type()), i.accountNo(),
					i.customerNo(), i.customerName(), i.eventDate(), i.balance(), i.delinquencyDays()));
			total += i.balance();
		}
		line(out, new CreditReportLayout.Trailer("T", items.size(), total));
		return out.toByteArray();
	}

	@Override
	public SendReceipt send(String fileName) {
		return calls.call(INSTITUTION, "보고 파일 전송", fileName, () -> {
			byte[] content = files.read("credit-report/outbound", fileName);
			files.write("credit-report/sent", fileName, content);
			files.write("credit-report/inbound", resultName(fileName), result(content));
			return new SendReceipt("RCPT-" + fileName.replace(".dat", ""));
		}, r -> new CallLogger.Outcome("SENT", true));
	}

	@Override
	public Optional<List<ItemResult>> fetchResult(String fileName) {
		return calls.call(INSTITUTION, "결과 파일 수신", fileName, () -> {
			if (!files.exists("credit-report/inbound", resultName(fileName))) {
				return Optional.<List<ItemResult>>empty();
			}
			List<ItemResult> results = new ArrayList<>();
			for (byte[] line : CreditReportLayout.lines(files.read("credit-report/inbound", resultName(fileName)))) {
				if (line[0] == 'R') {
					CreditReportLayout.Result r = mapper.decode(line, CreditReportLayout.Result.class);
					results.add(new ItemResult(r.seq(), r.code(), r.message()));
				}
			}
			return Optional.of(results);
		}, r -> new CallLogger.Outcome(r.isPresent() ? "RECEIVED" : "NOT_READY", true));
	}

	@Override
	public byte[] read(String fileName) {
		return files.read("credit-report/outbound", fileName);
	}

	/** 기관 측 처리 흉내: 데이터 레코드마다 결과 레코드 하나 */
	byte[] result(byte[] report) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		List<byte[]> lines = CreditReportLayout.lines(report);
		CreditReportLayout.Header h = mapper.decode(lines.get(0), CreditReportLayout.Header.class);
		line(out, new CreditReportLayout.Header("H", "CRES", h.baseDate(), CreditReportLayout.SENDER));
		int count = 0;
		for (byte[] line : lines) {
			if (line[0] != 'D') {
				continue;
			}
			CreditReportLayout.Data d = mapper.decode(line, CreditReportLayout.Data.class);
			String code = OK;
			String message = "정상";
			if (d.name().isBlank() || d.accountNo().isBlank()) {
				code = "E101";
				message = "필수 항목 누락";
			} else if (d.eventDate() == null || d.eventDate().isAfter(h.baseDate())) {
				code = "E301";
				message = "일자 오류";
			} else if (d.seq() % 7 == 3) {
				code = "E201";
				message = "기관 측 계약 미등록";
			}
			line(out, new CreditReportLayout.Result("R", d.seq(), code, message));
			count++;
		}
		line(out, new CreditReportLayout.Trailer("T", count, 0));
		return out.toByteArray();
	}

	private void line(ByteArrayOutputStream out, Object record) {
		out.writeBytes(mapper.encode(record));
		out.write('\r');
		out.write('\n');
	}

	static String resultName(String fileName) {
		return fileName.replace(".dat", ".res");
	}
}
