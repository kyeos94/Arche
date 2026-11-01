package dev.arche.reporting.internal;

import java.util.Map;

/** 일보 구역·항목의 화면·엑셀 표시명. 모르는 항목(새 거래유형·상태)은 코드를 그대로 보여 준다 */
final class ReportLabels {

	private ReportLabels() {
	}

	static final Map<String, String> SECTIONS = Map.of(
			DailyReportBuilder.DISBURSEMENT, "실행·양수",
			DailyReportBuilder.POSTING, "원장 거래유형별 (순액)",
			DailyReportBuilder.ALLOCATION, "입금 충당 항목",
			DailyReportBuilder.ACCRUAL, "정산 이벤트 확정 이자 (R1)",
			DailyReportBuilder.CASH, "입금·정정",
			DailyReportBuilder.BALANCE, "마감 직후 잔액",
			DailyReportBuilder.STATUS, "상태별",
			DailyReportBuilder.BUCKET, "연체 구간별 (채권 잔액)");

	private static final Map<String, String> ITEMS = Map.ofEntries(
			Map.entry("DISBURSE", "실행"), Map.entry("ACQUIRE", "양수"), Map.entry("REPAYMENT", "입금 충당"),
			Map.entry("SUSPENSE_IN", "가수금 입고"), Map.entry("SUSPENSE_APPLY", "가수금 충당"),
			Map.entry("PREPAYMENT", "중도상환"), Map.entry("PREPAYMENT_FEE", "중도상환수수료"),
			Map.entry("REFUND", "반환 대기"), Map.entry("COST_CHARGE", "비용 적재"), Map.entry("WAIVER", "채무조정 감면"),
			Map.entry("ADJUSTMENT", "조정"), Map.entry("CHARGE_OFF", "상각"), Map.entry("SALE", "매각"),
			Map.entry("REPURCHASE", "환매"), Map.entry("PRINCIPAL", "원금"), Map.entry("LATE_INTEREST", "가산이자"),
			Map.entry("NORMAL_INTEREST", "정상이자"), Map.entry("COST", "비용"), Map.entry("FEE", "수수료"),
			Map.entry("PRE_ACQ_INTEREST", "양수 전 이자"), Map.entry("SUSPENSE", "가수금 증감"),
			Map.entry("PAYMENT_ALLOCATED", "계약 충당 입금"), Map.entry("UNMATCHED_OPEN", "미확인 입금 잔액"),
			Map.entry("CORRECTION", "정정 (R28)"), Map.entry("UNPAID_NORMAL_INTEREST", "미납 정상이자"),
			Map.entry("UNPAID_LATE_INTEREST", "미납 가산이자"), Map.entry("REFUND_PENDING", "반환 대기"),
			Map.entry("ACCRUED_NORMAL_INTEREST", "미수 정상이자 (조회용 추정)"),
			Map.entry("ACCRUED_LATE_INTEREST", "미수 가산이자 (조회용 추정)"), Map.entry("CLAIM", "채권 잔액 (R67)"),
			Map.entry("NORMAL", "정상"), Map.entry("DELINQUENT", "연체"), Map.entry("ACCELERATED", "기한이익상실"),
			Map.entry("WORKOUT", "채무조정"), Map.entry("PAID_OFF", "완제"), Map.entry("CHARGED_OFF", "상각"),
			Map.entry("SOLD", "매각"));

	static String section(String section) {
		return SECTIONS.getOrDefault(section, section);
	}

	static String item(String section, String item) {
		if (section.equals(DailyReportBuilder.BUCKET)) {
			Bucket b = Bucket.parse(item);
			return b == null ? item : b.label();
		}
		if (section.equals(DailyReportBuilder.BALANCE) && item.equals("PRINCIPAL")) {
			return "원금 잔액";
		}
		if (section.equals(DailyReportBuilder.BALANCE) && item.equals("COST")) {
			return "미회수 비용";
		}
		if (section.equals(DailyReportBuilder.BALANCE) && item.equals("SUSPENSE")) {
			return "가수금 잔액";
		}
		if (section.equals(DailyReportBuilder.BALANCE) && item.equals("PRE_ACQ_INTEREST")) {
			return "양수 전 미수이자";
		}
		return ITEMS.getOrDefault(item, item);
	}

	static String status(String status) {
		return ITEMS.getOrDefault(status, status);
	}
}
