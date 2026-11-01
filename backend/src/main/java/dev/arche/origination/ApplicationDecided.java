package dev.arche.origination;

/**
 * 신청 결과 확정 이벤트 (승인·거절·철회). 대출 비교 플랫폼 결과 통보(R57)가 커밋 후 비동기로 받는다.
 * Spring Modulith 이벤트 발행 기록에 남아 리스너가 실패해도 유실되지 않는다.
 *
 * @param result APPROVED / REJECTED / WITHDRAWN
 */
public record ApplicationDecided(long applicationId, String applicationNo, String result) {
}
