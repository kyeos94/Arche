package dev.arche.note.internal;

import dev.arche.servicing.AdjustmentDecided;
import dev.arche.servicing.AdjustmentRequested;
import dev.arche.staff.CurrentStaff;
import dev.arche.staff.Permission;
import dev.arche.staff.StaffDirectory;
import java.util.List;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 시스템 쪽지 (R74): 결재 요청이 들어오면 결재할 수 있는 직원에게, 결재가 끝나면 요청자에게 알린다.
 * 요청·결재 트랜잭션이 커밋된 뒤 받으므로 쪽지가 실패해도 결재는 되돌아가지 않고, Spring Modulith 발행 기록으로 다시 보낼 수 있다.
 */
@Component
class SystemNotes {

	private final NoteService notes;
	private final StaffDirectory staff;

	SystemNotes(NoteService notes, StaffDirectory staff) {
		this.notes = notes;
		this.staff = staff;
	}

	@ApplicationModuleListener
	void on(AdjustmentRequested e) {
		// 결재 권한(권한 관리에서 바꾼 것 포함)이 있는 직원. 작성자 ≠ 승인자라 요청자는 뺀다
		List<Long> approvers = staff.active().stream().filter(s -> s.id() != e.requestedBy())
				.filter(s -> s.has(Permission.LOAN_ADJUST_APPROVE)).map(CurrentStaff::id).toList();
		notes.sendSystem(approvers, "[결재 요청] " + e.typeLabel() + " " + e.requestNo(),
				e.requestedByName() + " 님이 계약 " + e.accountNo() + "의 " + e.typeLabel() + " 결재를 요청했습니다.\n"
						+ "여신관리 > 조정 결재에서 승인하거나 반려하세요.",
				new NoteService.Ref(NoteService.RefType.LOAN, e.accountId(), e.accountNo()));
	}

	@ApplicationModuleListener
	void on(AdjustmentDecided e) {
		String result = e.approved() ? "승인" : "반려";
		notes.sendSystem(List.of(e.requestedBy()), "[결재 " + result + "] " + e.typeLabel() + " " + e.requestNo(),
				e.decidedByName() + " 님이 계약 " + e.accountNo() + "의 " + e.typeLabel() + " 요청(" + e.requestNo() + ")을 "
						+ result + "했습니다.",
				new NoteService.Ref(NoteService.RefType.LOAN, e.accountId(), e.accountNo()));
	}
}
