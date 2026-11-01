package dev.arche.staff;

import static dev.arche.staff.Role.COLLECTION_LEAD;
import static dev.arche.staff.Role.COLLECTOR;
import static dev.arche.staff.Role.HEAD;
import static dev.arche.staff.Role.SYSTEM_ADMIN;
import static dev.arche.staff.Role.TELLER;
import static dev.arche.staff.Role.UNDERWRITER;
import static dev.arche.staff.Role.UNDERWRITING_LEAD;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 권한 코드 = 정책서 9.1 역할별 권한표. 권한 문자열은 {@code @PreAuthorize("hasAuthority('loan.read')")}에 그대로 쓴다.
 * 승인이 필요한 작업은 작성자와 승인자가 달라야 한다 (서비스에서 검사).
 * <p>
 * enum에 적힌 역할은 정책서 기본값이다. 시스템관리자가 권한 관리 화면에서 바꾼 부분(R69)은 staff 모듈이
 * {@link #applyOverrides}로 넣고, {@link #grantedTo}는 바뀐 값을 우선한다.
 */
public enum Permission {

	APPLICATION_REVIEW("application.review", UNDERWRITER, UNDERWRITING_LEAD, HEAD),
	/** 심사 승인 1,000만원 이하 */
	APPLICATION_APPROVE_SMALL("application.approve.small", UNDERWRITER, UNDERWRITING_LEAD, HEAD),
	/** 심사 승인 1,000만원 초과 */
	APPLICATION_APPROVE_LARGE("application.approve.large", UNDERWRITING_LEAD, HEAD),
	/** 계약 조회. 채권담당은 담당 건, 채권팀장은 팀 전체 ({@link DataScope}) */
	LOAN_READ("loan.read", COLLECTOR, COLLECTION_LEAD, TELLER, HEAD, SYSTEM_ADMIN),
	CUSTOMER_ID_REVEAL("customer.id.reveal", UNDERWRITING_LEAD, COLLECTION_LEAD, HEAD),
	/** 입금 등록·미확인 입금 매칭 */
	PAYMENT_REGISTER("payment.register", COLLECTOR, COLLECTION_LEAD, TELLER, HEAD),
	WITHDRAWAL_CREATE("withdrawal.create", TELLER),
	WITHDRAWAL_APPROVE_SMALL("withdrawal.approve.small", COLLECTION_LEAD, HEAD),
	/** 1,000만원 초과 출금은 팀장 + 본부장 2단계 */
	WITHDRAWAL_APPROVE_LARGE("withdrawal.approve.large", COLLECTION_LEAD, HEAD),
	/** 금리·약정일 변경 */
	TERM_CHANGE("loan.term.change", COLLECTION_LEAD, HEAD),
	WORKOUT_LATE_INTEREST("workout.waive.late", COLLECTOR, COLLECTION_LEAD, HEAD),
	WORKOUT_NORMAL_INTEREST("workout.waive.normal", COLLECTION_LEAD, HEAD),
	WORKOUT_PRINCIPAL("workout.waive.principal", HEAD),
	CHARGE_OFF_SALE_CONFIRM("loan.chargeoff.confirm", HEAD),
	EXCEL_DOWNLOAD("excel.download", UNDERWRITING_LEAD, COLLECTION_LEAD, TELLER, HEAD),
	/** 직원·권한·코드·배치 관리 */
	ADMIN("admin.manage", SYSTEM_ADMIN),
	/** 마감 후 거래 취소 (R28: 시스템관리자 처리 + 채권팀장 승인) */
	CLOSED_TX_CANCEL("payment.cancel.closed", SYSTEM_ADMIN),
	/** 마감 후 취소 승인 (R28) */
	CLOSED_TX_CANCEL_APPROVE("payment.cancel.closed.approve", COLLECTION_LEAD),
	/** 계약 직접 생성·양수 등록 (R34: 본부장만) */
	LOAN_CREATE("loan.create", HEAD),
	/** 추심 연락 기록·분할납부 약속 등록 (R37) */
	COLLECTION_ACTIVITY("collection.activity", COLLECTOR, COLLECTION_LEAD, HEAD),
	/** 소멸시효 중단 사유(지급명령·압류·판결 확정) 기록 (R37) */
	LEGAL_EVENT("collection.legal", COLLECTION_LEAD, HEAD),
	/** 보고서 조회 (R68: 개인정보 없는 회사 전체 집계 — 엑셀 다운로드 권한자 + 시스템관리자) */
	REPORT_READ("report.read", UNDERWRITING_LEAD, COLLECTION_LEAD, TELLER, HEAD, SYSTEM_ADMIN),
	/** 수기 조정·상계·상태 되돌리기 요청 (R42~R44) */
	LOAN_ADJUST_REQUEST("loan.adjust.request", COLLECTION_LEAD, HEAD),
	/** 수기 조정·상계·상태 되돌리기 승인 (R42~R44, 작성자 ≠ 승인자) */
	LOAN_ADJUST_APPROVE("loan.adjust.approve", HEAD),
	/** 추심 중지 등록·해제 (R45·R46) */
	COLLECTION_STOP("collection.stop", COLLECTION_LEAD, HEAD),
	/** 심사자 수동 배정·재배정, 심사자 프로필(담당 상품·부재) 관리 (R55) */
	APPLICATION_ASSIGN("application.assign", UNDERWRITING_LEAD, HEAD),
	/** 중개업자 등록·API 키·수수료율 관리, 불완전판매 환수·환수 점검 (R56) */
	BROKER_MANAGE("broker.manage", UNDERWRITING_LEAD, HEAD);

	private final String code;
	private final Set<Role> roles;

	/** 정책서 기본값과 다르게 바꾼 권한 (R69). 키가 없으면 기본값 */
	private static volatile Map<Permission, Map<Role, Boolean>> overrides = Map.of();

	Permission(String code, Role first, Role... rest) {
		this.code = code;
		this.roles = EnumSet.of(first, rest);
	}

	public String code() {
		return code;
	}

	/** 지금 적용 중인 권한 (권한 관리에서 바꾼 값 우선) */
	public boolean grantedTo(Role role) {
		if (role == Role.DEMO_ALL) {
			return true;
		}
		Boolean changed = overrides.getOrDefault(this, Map.of()).get(role);
		return changed != null ? changed : roles.contains(role);
	}

	/** 정책서 9.1 기본값 */
	public boolean grantedByDefault(Role role) {
		return role == Role.DEMO_ALL || roles.contains(role);
	}

	/** 권한 관리에서 바꾼 값 전체를 교체한다 (staff 모듈 전용). 빈 맵이면 정책서 기본값으로 돌아감 */
	public static void applyOverrides(Map<Permission, Map<Role, Boolean>> changed) {
		Map<Permission, Map<Role, Boolean>> copy = new EnumMap<>(Permission.class);
		changed.forEach((p, byRole) -> {
			if (!byRole.isEmpty()) {
				copy.put(p, Map.copyOf(new EnumMap<>(byRole)));
			}
		});
		overrides = Map.copyOf(copy);
	}

	public static Permission byCode(String code) {
		for (Permission p : values()) {
			if (p.code.equals(code)) {
				return p;
			}
		}
		throw new IllegalArgumentException("unknown permission " + code);
	}

	public static Set<Permission> of(Role role) {
		Set<Permission> result = EnumSet.noneOf(Permission.class);
		for (Permission p : values()) {
			if (p.grantedTo(role)) {
				result.add(p);
			}
		}
		return result;
	}
}
