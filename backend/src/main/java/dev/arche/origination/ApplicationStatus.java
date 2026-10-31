package dev.arche.origination;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 신청 상태 머신 (guide 2.2). enum + 전이 허용표.
 */
public enum ApplicationStatus {
	RECEIVED, PRE_SCREENING, ASSIGNED, UNDERWRITING, APPROVED, REJECTED, WITHDRAWN, CONTRACTED, DISBURSED;

	private static final Map<ApplicationStatus, Set<ApplicationStatus>> ALLOWED = Map.of(
			RECEIVED, EnumSet.of(PRE_SCREENING, REJECTED, WITHDRAWN),
			PRE_SCREENING, EnumSet.of(ASSIGNED, REJECTED, WITHDRAWN),
			ASSIGNED, EnumSet.of(UNDERWRITING, WITHDRAWN),
			UNDERWRITING, EnumSet.of(APPROVED, REJECTED, WITHDRAWN),
			APPROVED, EnumSet.of(CONTRACTED, WITHDRAWN),
			CONTRACTED, EnumSet.of(DISBURSED),
			REJECTED, EnumSet.noneOf(ApplicationStatus.class),
			WITHDRAWN, EnumSet.noneOf(ApplicationStatus.class),
			DISBURSED, EnumSet.noneOf(ApplicationStatus.class));

	public boolean canMoveTo(ApplicationStatus next) {
		return ALLOWED.get(this).contains(next);
	}
}
