package dev.arche.document.internal;

import dev.arche.common.BusinessException;
import dev.arche.common.Money;
import dev.arche.servicing.LoanAccounts.DocumentView;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 양식 치환 문자열 (R72). 양식 본문의 {@code {{이름}}}을 계약 값으로 바꾼다.
 * <ul>
 * <li>값은 모두 HTML로 이스케이프한다 (고객 이름·주소에 태그가 들어 있어도 양식이 깨지거나 스크립트가 실행되지 않게)</li>
 * <li>모르는 이름은 양식을 저장할 때 거절하고, 렌더링 중 값이 없으면 '-'로 채운다 (빈 칸이 고객에게 나가지 않게)</li>
 * </ul>
 */
final class DocumentPlaceholders {

	/** 쓸 수 있는 치환 문자열과 설명 (화면 도움말에 그대로 쓴다) */
	static final Map<String, String> CATALOG = catalog();

	private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([^{}\\s]+)\\s*}}");

	private DocumentPlaceholders() {
	}

	/** 회사 정보 (가상 값, application.yml arche.company) */
	record Company(String name, String address, String phone, String registrationNo) {
	}

	static void validate(String body) {
		Matcher m = TOKEN.matcher(body);
		while (m.find()) {
			if (!CATALOG.containsKey(m.group(1))) {
				throw new BusinessException("TEMPLATE_INVALID",
						"알 수 없는 치환 문자열 {{" + m.group(1) + "}} — 쓸 수 있는 것: " + String.join(", ", CATALOG.keySet()));
			}
		}
	}

	/** 본문에 쓰인 치환 문자열 (중복 제거, 나온 순서) */
	static List<String> used(String body) {
		Matcher m = TOKEN.matcher(body);
		java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
		while (m.find()) {
			names.add(m.group(1));
		}
		return List.copyOf(names);
	}

	static String render(String body, Map<String, String> values) {
		Matcher m = TOKEN.matcher(body);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			String v = values.get(m.group(1));
			m.appendReplacement(sb, Matcher.quoteReplacement(escape(v == null || v.isBlank() ? "-" : v)));
		}
		m.appendTail(sb);
		return sb.toString();
	}

	static String escape(String s) {
		StringBuilder out = new StringBuilder(s.length());
		for (char c : s.toCharArray()) {
			switch (c) {
				case '<' -> out.append("&lt;");
				case '>' -> out.append("&gt;");
				case '&' -> out.append("&amp;");
				case '"' -> out.append("&quot;");
				case '\'' -> out.append("&#39;");
				default -> out.append(c);
			}
		}
		return out.toString();
	}

	/** 계약 값 → 치환 값 */
	static Map<String, String> values(DocumentView v, String customerName, String address, String productName,
			String staffName, Company company, String documentNo, LocalDate paymentDeadline) {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("회사명", company.name());
		m.put("회사주소", company.address());
		m.put("회사전화", company.phone());
		m.put("대부업등록번호", company.registrationNo());
		m.put("담당자", staffName);
		m.put("문서번호", documentNo);
		m.put("발행일", date(v.asOf()));
		m.put("고객명", customerName);
		m.put("고객주소", address);
		m.put("계약번호", v.accountNo());
		m.put("상품명", productName);
		m.put("대출일", date(v.disbursedOn()));
		m.put("대출금액", won(v.initialPrincipal()));
		m.put("약정이율", v.currentRate() == null ? null : v.currentRate().toString());
		m.put("원금잔액", won(v.principalBalance()));
		m.put("연체원금", won(v.overduePrincipal()));
		m.put("미납이자", won(v.unpaidNormalInterest()));
		m.put("가산이자", won(v.lateInterest()));
		m.put("비용", won(v.cost()));
		m.put("가수금", won(v.suspense()));
		m.put("청구금액", won(v.overdueAmount()));
		m.put("완제금액", won(v.payoffAmount()));
		m.put("연체시작일", date(v.delinquentSince()));
		m.put("연체일수", String.valueOf(v.delinquencyDays()));
		m.put("미납회차", String.valueOf(v.overdueInstallments()));
		m.put("최초미납약정일", date(v.oldestOverdueDueDate()));
		m.put("납부기한", date(paymentDeadline));
		m.put("입금계좌", v.virtualAccountNo() == null ? null : "가상계좌 " + v.virtualAccountNo());
		m.put("사유발생일", date(v.noticeReasonDate()));
		m.put("통지발송일", date(v.noticeSentOn()));
		m.put("도달간주일", date(v.noticeArrivalOn()));
		m.put("상실예정일", date(v.noticeAccelerationDate()));
		m.put("상실일", date(v.acceleratedOn()));
		return m;
	}

	static String won(Money m) {
		return m == null ? null : String.format("%,d", m.won());
	}

	static String date(LocalDate d) {
		return d == null ? null : d.getYear() + "년 " + d.getMonthValue() + "월 " + d.getDayOfMonth() + "일";
	}

	private static Map<String, String> catalog() {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("회사명", "회사 이름 (가상)");
		m.put("회사주소", "회사 주소 (가상)");
		m.put("회사전화", "대표전화");
		m.put("대부업등록번호", "대부업 등록번호 (가상)");
		m.put("담당자", "계약 담당 직원 이름");
		m.put("문서번호", "인쇄할 때 매기는 문서번호 (미리보기는 '미리보기')");
		m.put("발행일", "업무일자");
		m.put("고객명", "고객 이름");
		m.put("고객주소", "고객 주소 원문 (접근 기록에 남음)");
		m.put("계약번호", "계약번호");
		m.put("상품명", "상품 이름");
		m.put("대출일", "실행일·양수일");
		m.put("대출금액", "최초 원금");
		m.put("약정이율", "업무일자에 적용 중인 연이율");
		m.put("원금잔액", "원금 잔액");
		m.put("연체원금", "납부 기한이 지난 회차의 남은 원금 합계");
		m.put("미납이자", "업무일자까지 정상이자 (확정 미납 + 발생분)");
		m.put("가산이자", "업무일자까지 연체 가산이자");
		m.put("비용", "미회수 비용");
		m.put("가수금", "가수금 잔액");
		m.put("청구금액", "지금 갚아야 할 금액 (상실·만기 경과·매입채권은 완제 금액, R47 기준)");
		m.put("완제금액", "업무일자에 완제할 때 금액 (R8)");
		m.put("연체시작일", "가장 오래된 미납 약정일");
		m.put("연체일수", "연체일수");
		m.put("미납회차", "미납 회차 수");
		m.put("최초미납약정일", "가장 오래된 미납 회차의 약정일");
		m.put("납부기한", "발행일 + 7일 (휴일이면 다음 영업일)");
		m.put("입금계좌", "계약의 가상계좌");
		m.put("사유발생일", "최근 기한이익상실 사유 발생일 (R14)");
		m.put("통지발송일", "통지 발송일 (R15)");
		m.put("도달간주일", "도달일 (실제 도달일이 있으면 그 날, R15)");
		m.put("상실예정일", "상실 예정일 (R15)");
		m.put("상실일", "기한이익 상실 확정일");
		return java.util.Collections.unmodifiableMap(m);
	}
}
