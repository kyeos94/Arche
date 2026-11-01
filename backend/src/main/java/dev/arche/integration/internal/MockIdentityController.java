package dev.arche.integration.internal;

import dev.arche.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 가짜 인증 기관 화면의 API (Mock 전용). 인증 기관 쪽 화면이므로 우리 세션·CSRF와 무관하게 /api/integration 아래에 둔다.
 */
@RestController
@RequestMapping("/api/integration/mock-identity")
class MockIdentityController {

	record TxDto(String transactionId) {
	}

	record CompleteRequest(@NotNull Boolean success, String name, LocalDate birthDate) {
	}

	record CompleteResponse(String redirectUrl) {
	}

	private final MockIdentityVerificationAdapter adapter;

	MockIdentityController(MockIdentityVerificationAdapter adapter) {
		this.adapter = adapter;
	}

	@GetMapping("/{tx}")
	TxDto get(@PathVariable String tx) {
		return adapter.pending(tx).map(e -> new TxDto(tx)).orElseThrow(
				() -> new BusinessException("IDENTITY_TX_NOT_FOUND", "인증 거래가 없거나 만료되었습니다"));
	}

	@PostMapping("/{tx}")
	CompleteResponse complete(@PathVariable String tx, @Valid @RequestBody CompleteRequest r) {
		return new CompleteResponse(adapter.complete(tx, r.success(), r.name(), r.birthDate()));
	}
}
