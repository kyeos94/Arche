package dev.arche.common.web;

import dev.arche.common.BusinessException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class GlobalExceptionHandler {

	@ExceptionHandler(BusinessException.class)
	ResponseEntity<ApiError> business(BusinessException e) {
		HttpStatus status = switch (e.code()) {
			case "LOGIN_FAILED", "UNAUTHENTICATED" -> HttpStatus.UNAUTHORIZED;
			case "ACCOUNT_LOCKED" -> HttpStatus.LOCKED;
			case "FORBIDDEN" -> HttpStatus.FORBIDDEN;
			case "DUPLICATE" -> HttpStatus.CONFLICT;
			default -> e.code().endsWith("NOT_FOUND") ? HttpStatus.NOT_FOUND : HttpStatus.UNPROCESSABLE_CONTENT;
		};
		return ResponseEntity.status(status).body(ApiError.of(e.code(), e.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
		Map<String, String> fields = new LinkedHashMap<>();
		e.getBindingResult().getFieldErrors().forEach(f -> fields.put(f.getField(), f.getDefaultMessage()));
		return ResponseEntity.badRequest().body(new ApiError("INVALID_INPUT", "입력값을 확인하세요", fields));
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<ApiError> denied(AccessDeniedException e) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of("FORBIDDEN", "권한이 없습니다"));
	}

	@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
	ResponseEntity<ApiError> conflict(ObjectOptimisticLockingFailureException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(ApiError.of("CONFLICT", "다른 사용자가 먼저 수정했습니다. 새로고침 후 다시 시도하세요"));
	}
}
