package dev.arche.communication.internal;

import dev.arche.common.BusinessException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 템플릿 치환자: {고객명} {계약번호} {연체일수} {완제금액} {원금잔액} {납입일} {기준일}.
 * 모르는 치환자는 저장할 때 거절한다 (발송 시점에 빈 칸이 고객에게 나가지 않게).
 */
final class TemplateRenderer {

	static final List<String> PLACEHOLDERS = List.of("고객명", "계약번호", "연체일수", "완제금액", "원금잔액", "납입일", "기준일");
	private static final Pattern TOKEN = Pattern.compile("\\{([^{}]+)}");

	private TemplateRenderer() {
	}

	static void validate(String body) {
		Matcher m = TOKEN.matcher(body);
		while (m.find()) {
			if (!PLACEHOLDERS.contains(m.group(1))) {
				throw new BusinessException("TEMPLATE_INVALID",
						"알 수 없는 치환자 {" + m.group(1) + "} — 쓸 수 있는 것: " + String.join(", ", PLACEHOLDERS));
			}
		}
	}

	static String render(String body, Map<String, String> values) {
		Matcher m = TOKEN.matcher(body);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			String v = values.getOrDefault(m.group(1), m.group(0));
			m.appendReplacement(sb, Matcher.quoteReplacement(v));
		}
		m.appendTail(sb);
		return sb.toString();
	}

	static Map<String, String> values(String customerName, String accountNo, Integer delinquencyDays,
			String payoffAmount, String principalBalance, String nextDueDate, String businessDate) {
		Map<String, String> v = new LinkedHashMap<>();
		v.put("고객명", customerName);
		v.put("계약번호", accountNo);
		v.put("연체일수", delinquencyDays == null ? "0" : String.valueOf(delinquencyDays));
		v.put("완제금액", payoffAmount);
		v.put("원금잔액", principalBalance);
		v.put("납입일", nextDueDate == null ? "-" : nextDueDate);
		v.put("기준일", businessDate);
		return v;
	}
}
