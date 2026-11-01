package dev.arche.reporting;

import java.time.LocalDate;

/**
 * 보고서(reporting) 모듈 공개 API. 일마감이 호출한다 (R24·R65·R66).
 */
public interface DailyReports {

	/**
	 * 업무일자 D의 일보 스냅샷과 (D가 말일이면) 월마감 스냅샷을 저장한다.
	 * 일마감 ⑤ 잔액 대사 직후, 마감 기록·⑥ 업무일자 변경 전에 호출한다. 호출자는 D가 아직 마감되지 않았음을 보장하며,
	 * 같은 D의 스냅샷이 이미 있으면(마감 실패 후 재실행) 새로 쓴다.
	 */
	Snapshot snapshot(LocalDate businessDate);

	record Snapshot(LocalDate businessDate, int lines, long journalDebit, long journalCredit, int unmappedTypes,
			boolean monthEnd) {
	}
}
