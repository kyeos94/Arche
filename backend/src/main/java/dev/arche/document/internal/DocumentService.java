package dev.arche.document.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.collection.CollectionContacts;
import dev.arche.common.BusinessException;
import dev.arche.customer.Customers;
import dev.arche.product.ProductCatalog;
import dev.arche.servicing.LoanAccounts;
import dev.arche.servicing.LoanAccounts.DocumentView;
import dev.arche.staff.CurrentStaff;
import dev.arche.staff.DataScope;
import dev.arche.staff.StaffDirectory;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 양식 인쇄 (R72). 양식 원본(HTML)의 치환 문자열을 계약 값으로 채워 돌려준다. 실제 인쇄는 화면(브라우저)이 한다.
 * 인쇄는 발송이 아니므로 추심 연락 횟수(7일 7회)에 넣지 않는다. 실제로 보내면 우편 발송으로 따로 기록한다.
 */
@Service
class DocumentService {

	static final int MAX_BATCH = 200;
	/** 독촉장 납부 기한: 발행일 + 7일, 휴일이면 다음 영업일 */
	static final int PAYMENT_DAYS = 7;

	enum Purpose {
		PREVIEW, PRINT
	}

	record Template(String code, String name, String description, String targetRule, boolean collectionNotice,
			String css, String body, int version) {
	}

	record TemplateInfo(String code, String name, String description, boolean collectionNotice,
			List<String> placeholders) {
	}

	record Placeholder(String name, String description) {
	}

	record Document(long accountId, String accountNo, String customerName, String documentNo, String html) {
	}

	record Skipped(long accountId, String accountNo, String reason) {
	}

	record Result(String templateCode, String templateName, String css, String batchNo, List<Document> documents,
			List<Skipped> skipped) {
	}

	record PrintLogRow(long id, String batchNo, String templateName, String documentNo, LocalDate businessDate,
			String printedBy, OffsetDateTime printedAt) {
	}

	private final JdbcClient jdbc;
	private final LoanAccounts accounts;
	private final Customers customers;
	private final ProductCatalog products;
	private final StaffDirectory staff;
	private final CollectionContacts collection;
	private final BusinessDates businessDates;
	private final Clock clock;
	private final DocumentPlaceholders.Company company;

	DocumentService(JdbcClient jdbc, LoanAccounts accounts, Customers customers, ProductCatalog products,
			StaffDirectory staff, CollectionContacts collection, BusinessDates businessDates, Clock clock,
			@Value("${arche.company.name:예시대부(가상)}") String companyName,
			@Value("${arche.company.address:가상시 예시구 샘플로 123 (가상 주소)}") String companyAddress,
			@Value("${arche.company.phone:0000-0000}") String companyPhone,
			@Value("${arche.company.registration-no:가상-2026-0001}") String registrationNo) {
		this.jdbc = jdbc;
		this.accounts = accounts;
		this.customers = customers;
		this.products = products;
		this.staff = staff;
		this.collection = collection;
		this.businessDates = businessDates;
		this.clock = clock;
		this.company = new DocumentPlaceholders.Company(companyName, companyAddress, companyPhone, registrationNo);
	}

	@Transactional(readOnly = true)
	List<TemplateInfo> templates() {
		return loadAll().stream().map(t -> new TemplateInfo(t.code(), t.name(), t.description(), t.collectionNotice(),
				DocumentPlaceholders.used(t.body()))).toList();
	}

	List<Placeholder> placeholders() {
		return DocumentPlaceholders.CATALOG.entrySet().stream().map(e -> new Placeholder(e.getKey(), e.getValue()))
				.toList();
	}

	/**
	 * 계약마다 양식을 채운다. 대상이 아니거나 볼 수 없는 계약은 건너뛰고 이유를 돌려준다 (일괄 인쇄가 한 건 때문에 멈추지 않게).
	 * 인쇄(PRINT)면 계약마다 문서번호를 매기고 인쇄 이력을 남긴다.
	 */
	@Transactional
	Result render(String templateCode, List<Long> accountIds, Purpose purpose) {
		if (accountIds == null || accountIds.isEmpty()) {
			throw new BusinessException("NO_ACCOUNTS", "계약을 선택하세요");
		}
		List<Long> ids = List.copyOf(new LinkedHashSet<>(accountIds));
		if (ids.size() > MAX_BATCH) {
			throw new BusinessException("TOO_MANY", "한 번에 " + MAX_BATCH + "건까지 인쇄할 수 있습니다");
		}
		Template t = load(templateCode);
		CurrentStaff me = staff.current();
		DataScope scope = staff.loanScope();
		LocalDate today = businessDates.today();
		LocalDate deadline = businessDates.calendar().onOrNextBusinessDay(today.plusDays(PAYMENT_DAYS));
		String batchNo = purpose == Purpose.PRINT
				? "P" + today.format(DateTimeFormatter.BASIC_ISO_DATE) + "-"
						+ String.format("%05d", jdbc.sql("select nextval('document_print_batch_seq')").query(Long.class)
								.single())
				: null;
		List<Document> docs = new ArrayList<>();
		List<Skipped> skipped = new ArrayList<>();
		int seq = 0;
		for (long id : ids) {
			DocumentView v;
			try {
				v = accounts.documentView(id);
			} catch (BusinessException e) {
				skipped.add(new Skipped(id, null, "계약이 없습니다"));
				continue;
			}
			if (!scope.allows(v.assignedStaffId())) {
				skipped.add(new Skipped(id, v.accountNo(), "조회 권한이 없는 계약입니다"));
				continue;
			}
			Optional<String> notTarget = notTarget(t, v, today);
			if (notTarget.isPresent()) {
				skipped.add(new Skipped(id, v.accountNo(), notTarget.get()));
				continue;
			}
			String documentNo = purpose == Purpose.PRINT ? batchNo + "-" + String.format("%03d", ++seq) : "미리보기";
			String customerName = customers.get(v.customerId()).name();
			String address = customers.mailingAddress(v.customerId(),
					t.name() + (purpose == Purpose.PRINT ? " 인쇄 " + documentNo : " 미리보기"));
			String staffName = v.assignedStaffId() == null ? null
					: staff.byId(v.assignedStaffId()).map(CurrentStaff::name).orElse(null);
			String productName = productName(v.productCode());
			String html = DocumentPlaceholders.render(t.body(), DocumentPlaceholders.values(v, customerName, address,
					productName, staffName, company, documentNo, deadline));
			docs.add(new Document(id, v.accountNo(), customerName, documentNo, html));
			if (purpose == Purpose.PRINT) {
				jdbc.sql("""
						insert into document_print_log (batch_no, template_code, template_version, account_id,
						    document_no, business_date, printed_by, printed_at)
						values (:b, :t, :ver, :a, :d, :bd, :by, :at)
						""").param("b", batchNo).param("t", t.code()).param("ver", t.version()).param("a", id)
						.param("d", documentNo).param("bd", today).param("by", me.id())
						.param("at", OffsetDateTime.now(clock)).update();
			}
		}
		return new Result(t.code(), t.name(), t.css(), batchNo, docs, skipped);
	}

	/** 인쇄 대상이 아니면 그 이유 (R72) */
	Optional<String> notTarget(Template t, DocumentView v, LocalDate today) {
		Optional<String> common = notTarget(t.targetRule(), v);
		if (common.isPresent()) {
			return common;
		}
		if (t.collectionNotice()) {
			Optional<String> stop = collection.collectionStopReason(v.id());
			if (stop.isPresent()) {
				return stop;
			}
			return collection.blockingReason(v.id(), today);
		}
		return Optional.empty();
	}

	static Optional<String> notTarget(String rule, DocumentView v) {
		switch (v.status()) {
			case "PAID_OFF" -> {
				return Optional.of("완제된 계약입니다");
			}
			case "SOLD" -> {
				return Optional.of("매각된 계약입니다 (채권자가 바뀜)");
			}
			default -> {
			}
		}
		boolean accelerated = v.acceleratedOn() != null;
		if ("DEMAND".equals(rule)) {
			if (v.delinquentSince() == null && !accelerated) {
				return Optional.of("연체 중이 아닙니다");
			}
			if (!v.overdueAmount().isPositive()) {
				return Optional.of("갚아야 할 금액이 없습니다");
			}
			return Optional.empty();
		}
		if ("ACCELERATION".equals(rule)) {
			boolean noticed = "PENDING".equals(v.noticeStatus()) || "EXECUTED".equals(v.noticeStatus());
			if (!noticed && !accelerated) {
				return Optional.of("기한이익상실 통지 대상이 아닙니다 (사유 발생·상실 기록 없음)");
			}
			return Optional.empty();
		}
		return Optional.of("알 수 없는 양식 대상 규칙입니다");
	}

	@Transactional(readOnly = true)
	List<PrintLogRow> printLog(long accountId) {
		return jdbc.sql("""
				select l.id, l.batch_no, t.name as template_name, l.document_no, l.business_date, s.name as printed_by,
				       l.printed_at
				from document_print_log l
				join print_template t on t.code = l.template_code
				join staff s on s.id = l.printed_by
				where l.account_id = :a order by l.id desc limit 100
				""").param("a", accountId).query((rs, n) -> new PrintLogRow(rs.getLong("id"), rs.getString("batch_no"),
				rs.getString("template_name"), rs.getString("document_no"),
				rs.getObject("business_date", LocalDate.class), rs.getString("printed_by"),
				rs.getObject("printed_at", OffsetDateTime.class))).list();
	}

	private String productName(String code) {
		try {
			return products.current(code).name();
		} catch (RuntimeException e) {
			return code;
		}
	}

	private Template load(String code) {
		return loadAll().stream().filter(t -> t.code().equals(code)).findFirst()
				.orElseThrow(() -> new BusinessException("TEMPLATE_NOT_FOUND", "양식이 없습니다"));
	}

	private List<Template> loadAll() {
		return jdbc.sql("""
				select code, name, description, target_rule, collection_notice, css, body, version
				from print_template where active order by sort_order, code
				""").query((rs, n) -> new Template(rs.getString("code"), rs.getString("name"),
				rs.getString("description"), rs.getString("target_rule"), rs.getBoolean("collection_notice"),
				rs.getString("css"), rs.getString("body"), rs.getInt("version"))).list();
	}
}
