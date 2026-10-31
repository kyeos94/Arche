package dev.arche.common.web;

import java.util.Map;

/** API 오류 응답 */
public record ApiError(String code, String message, Map<String, String> fieldErrors) {

	public static ApiError of(String code, String message) {
		return new ApiError(code, message, Map.of());
	}
}
