package dev.arche.creditinfo.internal;

import dev.arche.integration.CreditReportingPort.RecordType;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 신용정보 보고 대상 판정 (R61). 기관에 반영된 마지막 상태와 지금 계약 상태를 비교해 보고할 사유를 낸다.
 * <ul>
 * <li>개설: 아직 보고하지 않은 계약 (보고일 기준)</li>
 * <li>연체 등록: 연체 90일째(연체 1일째부터 세어) 이상이고 아직 등록 전. 사유 발생일 = 90일째</li>
 * <li>연체 해제: 등록된 뒤 연체가 모두 해소되었거나 완제. 연체일수가 90일 아래로 줄기만 한 것은 해제 아님</li>
 * <li>종료: 완제. 사유 발생일 = 완제일</li>
 * </ul>
 */
final class ReportPlanner {

	static final int DELINQUENCY_REPORT_DAY = 90;

	/** 지금 계약 상태. delinquencyStartedOn은 연체 1일째(R38), 연체가 없으면 null */
	record Snapshot(long accountId, @Nullable LocalDate delinquencyStartedOn, @Nullable LocalDate paidOffOn,
			long principalBalance) {
	}

	/** 기관에 반영된 상태 (정상 결과를 받은 보고만) */
	record Reported(boolean opened, boolean delinquent, boolean closed) {
		static final Reported NONE = new Reported(false, false, false);
	}

	record Planned(RecordType type, LocalDate eventDate, long balance, int delinquencyDays) {
	}

	private ReportPlanner() {
	}

	static int delinquencyDays(@Nullable LocalDate startedOn, LocalDate baseDate) {
		if (startedOn == null || startedOn.isAfter(baseDate)) {
			return 0;
		}
		return (int) ChronoUnit.DAYS.between(startedOn, baseDate) + 1;
	}

	static List<Planned> plan(Snapshot s, Reported r, LocalDate baseDate) {
		List<Planned> out = new ArrayList<>();
		if (r.closed()) {
			return out;
		}
		boolean paidOff = s.paidOffOn() != null && !s.paidOffOn().isAfter(baseDate);
		int days = paidOff ? 0 : delinquencyDays(s.delinquencyStartedOn(), baseDate);
		if (!r.opened()) {
			out.add(new Planned(RecordType.OPEN, baseDate, s.principalBalance(), days));
		}
		if (!paidOff && !r.delinquent() && days >= DELINQUENCY_REPORT_DAY) {
			out.add(new Planned(RecordType.DELINQUENCY,
					s.delinquencyStartedOn().plusDays(DELINQUENCY_REPORT_DAY - 1), s.principalBalance(), days));
		}
		if (r.delinquent() && (paidOff || days == 0)) {
			out.add(new Planned(RecordType.RELEASE, paidOff ? s.paidOffOn() : baseDate, s.principalBalance(), 0));
		}
		if (paidOff) {
			out.add(new Planned(RecordType.CLOSE, s.paidOffOn(), 0, 0));
		}
		return out;
	}

	/** 정상 결과를 받은 보고를 반영한 새 상태 */
	static Reported apply(Reported r, RecordType type) {
		return switch (type) {
			case OPEN -> new Reported(true, r.delinquent(), r.closed());
			case DELINQUENCY -> new Reported(r.opened(), true, r.closed());
			case RELEASE -> new Reported(r.opened(), false, r.closed());
			case CLOSE -> new Reported(r.opened(), r.delinquent(), true);
		};
	}
}
