package dev.arche.creditinfo.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.arche.integration.CreditReportingPort.RecordType;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 신용정보 보고 대상 판정 (R61) */
class ReportPlannerTest {

	private static final LocalDate BASE = LocalDate.of(2026, 11, 1);
	private static final ReportPlanner.Reported OPENED = new ReportPlanner.Reported(true, false, false);
	private static final ReportPlanner.Reported REGISTERED = new ReportPlanner.Reported(true, true, false);

	@Test
	void 처음_보는_계약은_개설로_보고한다() {
		assertThat(types(snapshot(null, null), ReportPlanner.Reported.NONE)).containsExactly(RecordType.OPEN);
		assertThat(types(snapshot(null, null), OPENED)).isEmpty();
	}

	@Test
	void 연체_90일째에_등록하고_사유일은_90일째() {
		// 연체 1일째 08-04 → 90일째 11-01
		LocalDate start = LocalDate.of(2026, 8, 4);
		assertThat(ReportPlanner.delinquencyDays(start, BASE)).isEqualTo(90);
		var plan = ReportPlanner.plan(snapshot(start, null), OPENED, BASE);
		assertThat(plan).singleElement().satisfies(p -> {
			assertThat(p.type()).isEqualTo(RecordType.DELINQUENCY);
			assertThat(p.eventDate()).isEqualTo(BASE);
			assertThat(p.delinquencyDays()).isEqualTo(90);
		});
		assertThat(types(snapshot(start.plusDays(1), null), OPENED)).isEmpty(); // 89일째
		assertThat(types(snapshot(start, null), REGISTERED)).isEmpty(); // 이미 등록
	}

	@Test
	void 연체가_모두_풀리거나_완제되면_해제하고_완제는_종료로_보고한다() {
		assertThat(types(snapshot(LocalDate.of(2026, 10, 1), null), REGISTERED)).isEmpty(); // 31일째: 아직 연체
		assertThat(types(snapshot(null, null), REGISTERED)).containsExactly(RecordType.RELEASE);
		LocalDate paid = LocalDate.of(2026, 10, 30);
		var plan = ReportPlanner.plan(snapshot(LocalDate.of(2026, 5, 1), paid), REGISTERED, BASE);
		assertThat(plan).extracting(ReportPlanner.Planned::type).containsExactly(RecordType.RELEASE, RecordType.CLOSE);
		assertThat(plan).extracting(ReportPlanner.Planned::eventDate).containsOnly(paid);
		assertThat(types(snapshot(null, paid), new ReportPlanner.Reported(true, false, true))).isEmpty();
	}

	@Test
	void 정상_결과만_상태를_올린다() {
		var r = ReportPlanner.apply(ReportPlanner.Reported.NONE, RecordType.OPEN);
		r = ReportPlanner.apply(r, RecordType.DELINQUENCY);
		assertThat(r).isEqualTo(REGISTERED);
		assertThat(ReportPlanner.apply(r, RecordType.RELEASE)).isEqualTo(OPENED);
	}

	private static ReportPlanner.Snapshot snapshot(LocalDate delinquencyStart, LocalDate paidOff) {
		return new ReportPlanner.Snapshot(1, delinquencyStart, paidOff, 5_000_000);
	}

	private static List<RecordType> types(ReportPlanner.Snapshot s, ReportPlanner.Reported r) {
		return ReportPlanner.plan(s, r, BASE).stream().map(ReportPlanner.Planned::type).toList();
	}
}
