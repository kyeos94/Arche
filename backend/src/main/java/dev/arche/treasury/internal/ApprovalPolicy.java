package dev.arche.treasury.internal;

import dev.arche.common.BusinessException;
import dev.arche.staff.Permission;
import dev.arche.staff.Role;

/**
 * 출금 승인 단계 (정책서 7장 "작성자·승인자 분리, 1,000만원 초과는 팀장 + 본부장 2단계", 9.1, R63).
 * <ul>
 * <li>1,000만원 이하: 채권팀장 또는 본부장 1명이 승인하면 끝</li>
 * <li>1,000만원 초과: 1단계 채권팀장 → 2단계 본부장 (순서 고정). 두 승인자는 서로 다른 사람</li>
 * <li>작성자는 어느 단계도 승인할 수 없다</li>
 * </ul>
 * 데모 전용 역할(DEMO_ALL)은 어느 단계든 맡을 수 있지만 사람 분리 검사는 그대로 받는다.
 */
final class ApprovalPolicy {

	enum Step {
		FIRST, FINAL
	}

	private ApprovalPolicy() {
	}

	static Step next(boolean twoStep, Withdrawal.Status status, long makerId, Long firstApproverId, long approverId,
			Role role) {
		if (!status.open()) {
			throw new BusinessException("WITHDRAWAL_CLOSED", "승인할 수 없는 상태입니다");
		}
		if (approverId == makerId) {
			throw new BusinessException("FORBIDDEN", "작성자는 승인할 수 없습니다");
		}
		if (!twoStep) {
			if (!Permission.WITHDRAWAL_APPROVE_SMALL.grantedTo(role)) {
				throw new BusinessException("FORBIDDEN", "출금 승인 권한이 없습니다");
			}
			return Step.FINAL;
		}
		if (!Permission.WITHDRAWAL_APPROVE_LARGE.grantedTo(role)) {
			throw new BusinessException("FORBIDDEN", "출금 승인 권한이 없습니다");
		}
		if (status == Withdrawal.Status.REQUESTED) {
			if (role != Role.COLLECTION_LEAD && role != Role.DEMO_ALL) {
				throw new BusinessException("FORBIDDEN", "1,000만원 초과 출금의 1단계 승인은 채권팀장이 합니다");
			}
			return Step.FIRST;
		}
		if (role != Role.HEAD && role != Role.DEMO_ALL) {
			throw new BusinessException("FORBIDDEN", "1,000만원 초과 출금의 2단계 승인은 본부장이 합니다");
		}
		if (firstApproverId != null && firstApproverId == approverId) {
			throw new BusinessException("FORBIDDEN", "1단계 승인자는 2단계를 승인할 수 없습니다");
		}
		return Step.FINAL;
	}

	/** 반려는 지금 단계를 승인할 수 있는 사람만 */
	static void checkReject(boolean twoStep, Withdrawal.Status status, long makerId, Long firstApproverId,
			long approverId, Role role) {
		next(twoStep, status, makerId, firstApproverId, approverId, role);
	}
}
