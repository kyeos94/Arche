package dev.arche.integration.internal;

import dev.arche.integration.TransferPort;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Mock 펌뱅킹 송금 (guide 11.1 "지연·타임아웃·실패 시나리오 주입"). 받는 계좌번호 끝자리로 시나리오를 고른다.
 * <ul>
 * <li>끝자리 9: 즉시 실패 (계좌 오류)</li>
 * <li>끝자리 8: 응답 지연 → 타임아웃(UNKNOWN). 결과 조회하면 성공</li>
 * <li>끝자리 7: 응답 지연 → 타임아웃(UNKNOWN). 결과 조회하면 실패</li>
 * <li>끝자리 6: 예금주 불일치</li>
 * <li>그 외: 지연 후 성공</li>
 * </ul>
 * 같은 멱등 키의 두 번째 요청은 송금하지 않고 처음 결과를 돌려준다. 결과 조회는 서버가 다시 떠도 같은 답이 나오도록
 * 원 요청의 계좌번호 규칙으로 정한다.
 */
@Component
class MockTransferAdapter implements TransferPort {

	static final String INSTITUTION = "가상 펌뱅킹";

	private final CallLogger calls;
	private final long delayMs;
	private final long timeoutMs;
	private final Map<String, TransferResult> done = new ConcurrentHashMap<>();

	MockTransferAdapter(CallLogger calls, @Value("${arche.integration.transfer.delay-ms:300}") long delayMs,
			@Value("${arche.integration.transfer.timeout-ms:1500}") long timeoutMs) {
		this.calls = calls;
		this.delayMs = delayMs;
		this.timeoutMs = timeoutMs;
	}

	@Override
	public HolderCheck verifyHolder(String bankName, String accountNo, String holderName) {
		return calls.call(INSTITUTION, "예금주 확인", bankName + " " + TransferPort.maskAccount(accountNo), () -> {
			boolean matched = holderName != null && !holderName.isBlank() && last(accountNo) != '6';
			return new HolderCheck(matched, matched ? "OK" : "HOLDER_MISMATCH");
		}, h -> new CallLogger.Outcome(h.resultCode(), h.matched()));
	}

	@Override
	public TransferResult transfer(TransferRequest r) {
		return calls.call(INSTITUTION, "송금 요청", r.toString(), () -> {
			TransferResult previous = done.get(r.idempotencyKey());
			if (previous != null) {
				return previous;
			}
			char c = last(r.accountNo());
			String txId = "TR-" + r.idempotencyKey();
			TransferResult result;
			if (c == '9') {
				result = new TransferResult(Status.FAILED, txId, "E_ACCOUNT", "받는 계좌 오류");
			} else if (c == '8' || c == '7') {
				sleep(timeoutMs);
				result = new TransferResult(Status.UNKNOWN, txId, "TIMEOUT", "응답 시간 초과 — 결과 조회로 확정");
			} else {
				sleep(delayMs);
				result = new TransferResult(Status.SUCCEEDED, txId, "OK", "송금 완료");
			}
			if (result.status() != Status.UNKNOWN) {
				done.put(r.idempotencyKey(), result);
			}
			return result;
		}, t -> new CallLogger.Outcome(t.resultCode(), t.status() == Status.SUCCEEDED));
	}

	@Override
	public TransferResult inquire(TransferRequest original) {
		String key = original.idempotencyKey();
		return calls.call(INSTITUTION, "결과 조회", key, () -> {
			TransferResult known = done.get(key);
			if (known != null) {
				return known;
			}
			char c = last(original.accountNo());
			String txId = "TR-" + key;
			TransferResult result;
			if (c == '9') {
				result = new TransferResult(Status.FAILED, txId, "E_ACCOUNT", "받는 계좌 오류");
			} else if (c == '7') {
				result = new TransferResult(Status.FAILED, txId, "E_REJECTED", "수취 은행 거절");
			} else {
				result = new TransferResult(Status.SUCCEEDED, txId, "OK", "송금 완료 (결과 조회로 확정)");
			}
			done.put(key, result);
			return result;
		}, t -> new CallLogger.Outcome(t.resultCode(), t.status() == Status.SUCCEEDED));
	}

	private static char last(String accountNo) {
		String digits = accountNo == null ? "" : accountNo.replaceAll("[^0-9]", "");
		return digits.isEmpty() ? '0' : digits.charAt(digits.length() - 1);
	}

	private static void sleep(long ms) {
		if (ms <= 0) {
			return;
		}
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
