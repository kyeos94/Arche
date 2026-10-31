package dev.arche.integration.internal;

import tools.jackson.databind.ObjectMapper;
import dev.arche.common.BusinessException;
import dev.arche.common.Money;
import dev.arche.integration.VirtualAccountPort;
import dev.arche.payment.Payments;
import dev.arche.servicing.AccountRef;
import dev.arche.servicing.LoanAccounts;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 가상계좌 입금 통지 웹훅 (MVP 한 줄기). 서명 검증 → 거래고유번호로 중복 통지 무시 → 계약 자동 충당.
 * 가상계좌번호로 계약을 못 찾으면 미확인 입금으로 보관한다. 같은 통지가 다시 와도 200 + DUPLICATE (재시도 안전).
 */
@RestController
@RequestMapping("/api/integration/virtual-account")
class VirtualAccountWebhook {

	private static final Logger log = LoggerFactory.getLogger(VirtualAccountWebhook.class);

	record Ack(String result, Long paymentId) {
	}

	private final VirtualAccountPort port;
	private final Payments payments;
	private final LoanAccounts accounts;
	private final ObjectMapper json;

	VirtualAccountWebhook(VirtualAccountPort port, Payments payments, LoanAccounts accounts, ObjectMapper json) {
		this.port = port;
		this.payments = payments;
		this.accounts = accounts;
		this.json = json;
	}

	@PostMapping("/deposits")
	ResponseEntity<Ack> deposit(@RequestBody String body,
			@RequestHeader(name = "X-Signature", required = false) String signature) {
		if (!port.verify(body, signature)) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new Ack("INVALID_SIGNATURE", null));
		}
		VirtualAccountPort.DepositNotice n = json.readValue(body, VirtualAccountPort.DepositNotice.class);
		Optional<Payments.PaymentView> existing = payments.findByBankTxId(n.bankTxId());
		if (existing.isPresent()) {
			return ResponseEntity.ok(new Ack("DUPLICATE", existing.get().id()));
		}
		Long accountId = accounts.findByVirtualAccountNo(n.virtualAccountNo()).map(AccountRef::id).orElse(null);
		try {
			Payments.PaymentView p = payments.register(new Payments.Deposit(accountId,
					Payments.Channel.VIRTUAL_ACCOUNT, n.bankDate(), Money.won(n.amount()), n.depositorName(),
					n.bankTxId()));
			log.info("가상계좌 입금 {} → {}", n.bankTxId(), accountId == null ? "미확인 입금" : "계약 " + accountId);
			return ResponseEntity.ok(new Ack(accountId == null ? "UNMATCHED" : "ALLOCATED", p.id()));
		} catch (BusinessException e) {
			if ("DUPLICATE".equals(e.code())) {
				return ResponseEntity.ok(new Ack("DUPLICATE", null));
			}
			throw e;
		} catch (DataIntegrityViolationException e) {
			// 동시에 같은 통지가 두 번 들어와 유니크 제약에 걸린 경우
			return ResponseEntity.ok(new Ack("DUPLICATE", null));
		}
	}
}
