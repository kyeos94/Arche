package dev.arche.payment.internal.bulk;

import dev.arche.calendar.BusinessDates;
import dev.arche.common.BusinessException;
import dev.arche.common.Money;
import dev.arche.payment.Payments;
import dev.arche.servicing.AccountRef;
import dev.arche.servicing.LoanAccounts;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 입금 일괄 등록 (MVP 한 줄기). 열: 계약번호, 입금일, 입금액, 입금자명, 거래고유번호.
 */
@Component
class PaymentBulkProcessor extends AbstractBulkProcessor<PaymentBulkProcessor.Line> {

	record Line(String accountNo, LocalDate bankDate, long amount, String depositorName, String bankTxId) {
	}

	private static final DateTimeFormatter SLASH = DateTimeFormatter.ofPattern("yyyy/MM/dd");

	private final LoanAccounts accounts;
	private final Payments payments;
	private final BusinessDates businessDates;

	PaymentBulkProcessor(LoanAccounts accounts, Payments payments, BusinessDates businessDates) {
		this.accounts = accounts;
		this.payments = payments;
		this.businessDates = businessDates;
	}

	@Override
	String type() {
		return "PAYMENT";
	}

	@Override
	List<String> headers() {
		return List.of("계약번호", "입금일", "입금액", "입금자명", "거래고유번호");
	}

	@Override
	Line parse(List<String> c) {
		if (c.get(0).isBlank()) {
			throw new BusinessException("BULK_ROW", "계약번호가 비었습니다");
		}
		LocalDate date = parseDate(c.get(1));
		long amount;
		try {
			amount = Long.parseLong(c.get(2).replace(",", "").replace("원", "").trim());
		} catch (NumberFormatException e) {
			throw new BusinessException("BULK_ROW", "입금액이 숫자가 아닙니다: " + c.get(2));
		}
		if (c.get(4).isBlank()) {
			throw new BusinessException("BULK_ROW", "거래고유번호가 비었습니다");
		}
		return new Line(c.get(0), date, amount, c.get(3), c.get(4));
	}

	private static LocalDate parseDate(String s) {
		try {
			return s.contains("/") ? LocalDate.parse(s, SLASH) : LocalDate.parse(s);
		} catch (DateTimeParseException e) {
			throw new BusinessException("BULK_ROW", "입금일 형식(yyyy-MM-dd)이 아닙니다: " + s);
		}
	}

	@Override
	String validate(Line v, List<Line> earlier) {
		if (v.amount() <= 0) {
			return "입금액은 0보다 커야 합니다";
		}
		if (v.bankDate().isAfter(businessDates.today())) {
			return "입금일이 업무일자 이후입니다";
		}
		Optional<AccountRef> account = accounts.findByAccountNo(v.accountNo());
		if (account.isEmpty()) {
			return "계약번호가 없습니다";
		}
		if (account.get().paidOffOn() != null) {
			return "완제된 계약입니다";
		}
		if (earlier.stream().anyMatch(e -> e.bankTxId().equals(v.bankTxId()))) {
			return "파일 안에서 거래고유번호가 중복됩니다";
		}
		if (payments.findByBankTxId(v.bankTxId()).isPresent()) {
			return "이미 처리한 거래고유번호입니다";
		}
		return null;
	}

	@Override
	String execute(Line v) {
		AccountRef account = accounts.findByAccountNo(v.accountNo())
				.orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "계약번호가 없습니다"));
		Payments.PaymentView p = payments.register(new Payments.Deposit(account.id(),
				Payments.Channel.BANK_TRANSFER, v.bankDate(), Money.won(v.amount()), v.depositorName(), v.bankTxId()));
		return "입금 #" + p.id();
	}

	@Override
	Line fromPayload(String payload) {
		String[] p = payload.split("\\|", -1);
		return new Line(p[0], LocalDate.parse(p[1]), Long.parseLong(p[2]), p[3], p[4]);
	}

	@Override
	String toPayload(Line v) {
		return String.join("|", v.accountNo(), v.bankDate().toString(), String.valueOf(v.amount()),
				v.depositorName() == null ? "" : v.depositorName(), v.bankTxId());
	}
}
