package dev.arche.integration.internal;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mock 플랫폼이 받은 결과 통보 확인용 (플랫폼 쪽 화면을 흉내 낸 것). 플랫폼 API 키로만 볼 수 있다.
 */
@RestController
@RequestMapping("/api/integration/mock-platform")
class MockPlatformController {

	private final MockLoanPlatformAdapter adapter;

	MockPlatformController(MockLoanPlatformAdapter adapter) {
		this.adapter = adapter;
	}

	@GetMapping("/notices")
	ResponseEntity<List<MockLoanPlatformAdapter.Received>> notices(
			@RequestHeader(name = "X-Platform-Key", required = false) String key) {
		if (!adapter.authenticate(key)) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		return ResponseEntity.ok(adapter.recent());
	}
}
