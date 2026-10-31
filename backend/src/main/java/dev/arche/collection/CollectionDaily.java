package dev.arche.collection;

import java.time.LocalDate;

/**
 * 일마감에서 부르는 추심 일 배치: 소멸시효 임박·완성 알림(G13), 분할납부 약속 판정과 해제(G19, R18),
 * 다음 날 08:00 자동 문자 생성(정책서 6장).
 */
public interface CollectionDaily {

	record Report(int limitationAlerts, int promiseMisses, int promisesTerminated, int messagesScheduled) {
	}

	/** judgeDate 기준으로 판정 (R24: 업무일자 D의 마감은 D+1로 판정) */
	Report run(LocalDate judgeDate);
}
