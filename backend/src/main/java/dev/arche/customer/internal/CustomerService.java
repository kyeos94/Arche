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
}

@Service
class CustomerService implements Customers {

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

	private Customer find(long id) {
		return customers.findById(id).orElseThrow(() -> new BusinessException("CUSTOMER_NOT_FOUND", "고객이 없습니다"));
	}
}
