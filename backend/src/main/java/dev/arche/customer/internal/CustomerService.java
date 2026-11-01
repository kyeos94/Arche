package dev.arche.customer.internal;

import dev.arche.common.BusinessException;
import dev.arche.common.crypto.BlindIndexer;
import dev.arche.common.jpa.NumberGenerator;
import dev.arche.customer.CustomerView;
import dev.arche.customer.Customers;
import dev.arche.staff.AccessLogger;
import dev.arche.staff.Permission;
import dev.arche.staff.StaffDirectory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

interface CustomerRepository extends JpaRepository<Customer, Long> {

	Optional<Customer> findByResidentNoBlindIndex(String blindIndex);

	List<Customer> findTop50ByPhoneBlindIndexOrderById(String blindIndex);

	List<Customer> findTop50ByNameContainingOrderById(String name);

	List<Customer> findByPhoneBlindIndexIsNullAndPhoneIsNotNull();

	@org.springframework.data.jpa.repository.Query("select c.id from Customer c where c.residentNoBlindIndex = :i")
	List<Long> idsByResidentIndex(@org.springframework.data.repository.query.Param("i") String blindIndex);

	@org.springframework.data.jpa.repository.Query("select c.id from Customer c where c.phoneBlindIndex = :i order by c.id")
	List<Long> idsByPhoneIndex(@org.springframework.data.repository.query.Param("i") String blindIndex,
			org.springframework.data.domain.Pageable page);

	@org.springframework.data.jpa.repository.Query("select c.id from Customer c where c.name like concat('%', :n, '%') order by c.id")
	List<Long> idsByName(@org.springframework.data.repository.query.Param("n") String name,
			org.springframework.data.domain.Pageable page);
}

@Service
class CustomerService implements Customers {

	static final int ID_MATCH_LIMIT = 5_000;

	private final CustomerRepository customers;
	private final BlindIndexer indexer;
	private final NumberGenerator numbers;
	private final AccessLogger accessLog;
	private final StaffDirectory staff;

	CustomerService(CustomerRepository customers, BlindIndexer indexer, NumberGenerator numbers,
			AccessLogger accessLog, StaffDirectory staff) {
		this.customers = customers;
		this.indexer = indexer;
		this.numbers = numbers;
		this.accessLog = accessLog;
		this.staff = staff;
	}

	@Override
	@Transactional
	public CustomerView registerOrFind(NewCustomer c) {
		String bidx = indexer.index(c.residentNo());
		return customers.findByResidentNoBlindIndex(bidx)
				.orElseGet(() -> customers.save(new Customer(numbers.next("customer_no_seq", "C"), c.name(),
						c.residentNo(), bidx, c.birthDate(), c.phone(), c.phone() == null ? null : indexer.index(c.phone()),
						c.address())))
				.view();
	}

	@Override
	@Transactional(readOnly = true)
	public CustomerView get(long id) {
		return find(id).view();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<CustomerView> findByResidentNo(String residentNo) {
		return customers.findByResidentNoBlindIndex(indexer.index(residentNo)).map(Customer::view);
	}

	@Override
	@Transactional(readOnly = true)
	public List<CustomerView> search(String query) {
		String q = query == null ? "" : query.trim();
		return switch (Customers.kindOf(q)) {
			case RESIDENT_NO -> customers.findByResidentNoBlindIndex(indexer.index(q)).map(Customer::view).stream()
					.toList();
			case PHONE -> customers.findTop50ByPhoneBlindIndexOrderById(indexer.index(q)).stream().map(Customer::view)
					.toList();
			case NAME -> q.length() < 2 ? List.of()
					: customers.findTop50ByNameContainingOrderById(q).stream().map(Customer::view).toList();
		};
	}

	@Override
	@Transactional(readOnly = true)
	public List<Long> idsMatching(SearchKind kind, String query) {
		String q = query == null ? "" : query.trim();
		if (q.isEmpty()) {
			return List.of();
		}
		var limit = org.springframework.data.domain.PageRequest.of(0, ID_MATCH_LIMIT);
		return switch (kind) {
			case RESIDENT_NO -> customers.idsByResidentIndex(indexer.index(q));
			case PHONE -> customers.idsByPhoneIndex(indexer.index(q), limit);
			case NAME -> customers.idsByName(q, limit);
		};
	}

	/** V6 이전에 등록된 고객의 휴대폰 블라인드 인덱스를 채운다 (HMAC 키는 애플리케이션에만 있음) */
	@Transactional
	int backfillPhoneIndex() {
		List<Customer> missing = customers.findByPhoneBlindIndexIsNullAndPhoneIsNotNull();
		missing.forEach(c -> c.indexPhone(indexer.index(c.phone())));
		return missing.size();
	}

	@Override
	@Transactional(readOnly = true)
	public String revealResidentNo(long id, String reason) {
		if (!staff.current().has(Permission.CUSTOMER_ID_REVEAL)) {
			throw new BusinessException("FORBIDDEN", "고객 식별번호 원문 조회 권한이 없습니다");
		}
		if (reason == null || reason.isBlank()) {
			throw new BusinessException("REASON_REQUIRED", "원문 조회 사유를 입력하세요");
		}
		Customer c = find(id);
		accessLog.log("CUSTOMER_ID_REVEAL", "CUSTOMER", String.valueOf(id), reason);
		return c.residentNo();
	}

	@Override
	@Transactional(readOnly = true)
	public String residentNoForCreditInquiry(long id, String consentRef) {
		if (consentRef == null || consentRef.isBlank()) {
			throw new BusinessException("CONSENT_REQUIRED", "신용조회 동의가 없습니다");
		}
		Customer c = find(id);
		accessLog.log("CREDIT_INQUIRY_ID_USE", "CUSTOMER", String.valueOf(id), "신용조회 (" + consentRef + ")");
		return c.residentNo();
	}

	@Override
	@Transactional(readOnly = true)
	public String mailingAddress(long id, String purpose) {
		Customer c = find(id);
		accessLog.log("CUSTOMER_ADDRESS_USE", "CUSTOMER", String.valueOf(id), purpose);
		return c.address() == null ? "" : c.address();
	}

	private Customer find(long id) {
		return customers.findById(id).orElseThrow(() -> new BusinessException("CUSTOMER_NOT_FOUND", "고객이 없습니다"));
	}
}
