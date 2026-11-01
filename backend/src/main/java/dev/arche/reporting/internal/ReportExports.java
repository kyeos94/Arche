package dev.arche.reporting.internal;

/**
 * 보고서 → 엑셀 시트. 화면과 같은 DTO를 쓴다 (보고서에는 개인정보가 없다, R68 ③).
 */
final class ReportExports {

	private ReportExports() {
	}

	@FunctionalInterface
	interface Writer {
		void write(XlsxWriter w);
	}

	static Writer daily(ReportController.DailyDto d) {
		return w -> {
			w.sheet("일보").title("일보 " + d.businessDate() + (d.closed() ? " (마감 스냅샷)" : " (미리보기)"))
					.header("구역", "항목", "코드", "건수", "금액");
			for (ReportController.LineDto l : d.lines()) {
				w.row(l.sectionLabel(), l.label(), l.item(), l.count(), l.amount());
			}
			w.blank().row("분개 차변", null, null, null, d.journalDebit()).row("분개 대변", null, null, null,
					d.journalCredit()).row("미매핑 거래유형", null, null, (long) d.unmappedCount());
		};
	}

	static Writer journal(ReportController.JournalDto j) {
		return w -> {
			w.sheet("계정별 합계").title("분개 " + j.businessDate() + (j.balanced() ? " (차변 = 대변)" : " (차변 ≠ 대변)"))
					.header("계정", "계정명", "구분", "차변", "대변");
			for (ReportController.AccountTotalDto t : j.totals()) {
				w.row(t.accountCode(), t.accountName(), t.category(), t.debit(), t.credit());
			}
			w.row("합계", null, null, j.debit(), j.credit());
			w.sheet("분개").header("원장 거래", "계약번호", "거래유형", "역분개", "계정", "계정명", "차변", "대변", "적요", "미매핑");
			for (ReportController.JournalLineDto l : j.lines()) {
				w.row(l.entryId(), l.accountNo(), l.postingLabel(), l.reversal(), l.accountCode(), l.accountName(),
						l.debit(), l.credit(), l.memo(), l.unmapped());
			}
		};
	}

	static Writer rollRate(ReportController.RollRateDto r) {
		return w -> {
			w.sheet("전이율").title("연체 전이율 " + r.previousMonth() + " → " + r.month()
					+ (r.currentClosed() ? "" : " (당월 미리보기)"));
			String[] header = new String[3 + r.columns().size() * 2 + 2];
			header[0] = "전월 구간";
			header[1] = "건수";
			header[2] = "잔액";
			for (int i = 0; i < r.rows().getFirst().cells().size(); i++) {
				String label = r.rows().getFirst().cells().get(i).toLabel();
				header[3 + i * 2] = label + " 잔액";
				header[4 + i * 2] = label + " 비율(%)";
			}
			header[header.length - 2] = "악화율(%)";
			header[header.length - 1] = "개선율(%)";
			w.header(header);
			for (ReportController.RollRowDto row : r.rows()) {
				Object[] v = new Object[header.length];
				v[0] = row.fromLabel();
				v[1] = row.count();
				v[2] = row.balance();
				for (int i = 0; i < row.cells().size(); i++) {
					v[3 + i * 2] = row.cells().get(i).balance();
					v[4 + i * 2] = row.cells().get(i).balanceRate();
				}
				v[v.length - 2] = row.worsenRate();
				v[v.length - 1] = row.cureRate();
				w.row(v);
			}
		};
	}

	static Writer provision(ReportController.ProvisionDto p) {
		return w -> {
			w.sheet("대손충당금").title("대손충당금 기초 집계 " + (p.month() == null ? "현재" : p.month()) + " (기준일 "
					+ p.asOf() + ")").header("구간", "건수", "채권 잔액", "적립률(%)", "충당금");
			for (ReportController.ProvisionRowDto r : p.rows()) {
				w.row(r.label(), r.count(), r.claim(), r.rate(), r.provision());
			}
			w.row("합계", p.count(), p.claim(), null, p.provision());
		};
	}
}
