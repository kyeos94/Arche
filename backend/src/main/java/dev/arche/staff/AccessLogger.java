package dev.arche.staff;

/**
 * 개인정보 처리·로그인 접근 기록 (guide 8.2). 기록은 수정할 수 없다.
 */
public interface AccessLogger {

	void log(String action, String targetType, String targetId, String reason);
}
