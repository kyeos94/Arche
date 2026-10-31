package dev.arche.common;

/**
 * 업무 규칙 위반. code는 화면·API에서 그대로 노출되는 오류 코드.
 */
public class BusinessException extends RuntimeException {

	private final String code;

	public BusinessException(String code, String message) {
		super(message);
		this.code = code;
	}

	public String code() {
		return code;
	}
}
