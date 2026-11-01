package dev.arche.integration;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 신용정보 보고(정보집중) 포트 (guide 11.1 CreditReportingPort): 일별 파일 생성 → 전송 → 결과 파일 수신.
 * 파일 레이아웃은 기관이 정하는 것이라 어댑터가 가진다. 이 저장소의 레이아웃은 자체 설계한 일반형이며 실제 기관 형식이 아니다.
 */
public interface CreditReportingPort {

	/** 보고 사유 */
	enum RecordType {
		/** 계약 개설 */
		OPEN,
		/** 연체 등록 */
		DELINQUENCY,
		/** 연체 해제 */
		RELEASE,
		/** 계약 종료 (완제) */
		CLOSE
	}

	record ReportItem(int seq, RecordType type, String accountNo, String customerNo, String customerName,
			LocalDate eventDate, long balance, int delinquencyDays) {

		@Override
		public String toString() {
			return "ReportItem[" + seq + ", " + type + ", " + accountNo + "]";
		}
	}

	record GeneratedFile(String fileName, int recordCount, int byteLength) {
	}

	record SendReceipt(String receiptNo) {
	}

	/** 결과 코드 "0000"이 정상, 그 외는 오류 */
	record ItemResult(int seq, String resultCode, String message) {

		public boolean ok() {
			return OK.equals(resultCode);
		}
	}

	String OK = "0000";

	/** 보고 파일을 만들어 보관한다 */
	GeneratedFile generate(LocalDate baseDate, List<ReportItem> items);

	SendReceipt send(String fileName);

	/** 결과 파일. 아직 오지 않았으면 빈 값 */
	Optional<List<ItemResult>> fetchResult(String fileName);

	/** 보관된 보고 파일 원본 (화면 다운로드용) */
	byte[] read(String fileName);
}
