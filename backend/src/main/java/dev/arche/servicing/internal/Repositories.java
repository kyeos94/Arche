package dev.arche.servicing.internal;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface LoanAccountRepository extends JpaRepository<LoanAccount, Long> {

	/** 계약 단위 락 (SELECT ... FOR UPDATE, R31) */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select a from LoanAccount a where a.id = :id")
	Optional<LoanAccount> lockById(@Param("id") long id);

	Optional<LoanAccount> findByAccountNo(String accountNo);

	Optional<LoanAccount> findByVirtualAccountNo(String virtualAccountNo);

	Optional<LoanAccount> findByApplicationId(Long applicationId);

	@Query("select a.id from LoanAccount a where a.status <> 'PAID_OFF' order by a.id")
	List<Long> findOpenIds();

	List<LoanAccount> findByPaidOffOn(LocalDate paidOffOn);

	List<LoanAccount> findTop50ByAccountNoContainingOrderByIdDesc(String accountNo);

	List<LoanAccount> findTop50ByCustomerIdInOrderByIdDesc(Collection<Long> customerIds);

	/**
	 * 일마감 판정 대상: 판정일까지 실행(양수)된 열린 계약 중 아직 그날까지 재생하지 않은 것.
	 * 실행일이 판정일보다 늦은 계약은 재생할 수 없고, 이미 더 뒤까지 재생한 계약을 되돌리지 않는다.
	 */
	@Query("select a.id from LoanAccount a where a.status <> 'PAID_OFF' and a.disbursedOn <= :date"
			+ " and a.asOf <= :date order by a.id")
	List<Long> findIdsToJudge(@Param("date") LocalDate date);

	@Query("""
			select a from LoanAccount a
			where (:status = '' or a.status = :status)
			  and (:allStaff = true or a.assignedStaffId in :staffIds)
			  and a.accountNo like concat('%', :accountNo, '%')
			  and (:customerIdsEmpty = true or a.customerId in :customerIds)
			""")
	/** status·accountNo는 null 대신 빈 문자열 (PostgreSQL이 null 파라미터를 bytea로 받아 LIKE가 깨짐) */
	Page<LoanAccount> search(@Param("status") String status, @Param("allStaff") boolean allStaff,
			@Param("staffIds") Collection<Long> staffIds, @Param("accountNo") String accountNo,
			@Param("customerIdsEmpty") boolean customerIdsEmpty, @Param("customerIds") Collection<Long> customerIds,
			Pageable pageable);

	@Query("""
			select a.status, count(a) from LoanAccount a
			where (:allStaff = true or a.assignedStaffId in :staffIds)
			group by a.status
			""")
	List<Object[]> countByStatus(@Param("allStaff") boolean allStaff, @Param("staffIds") Collection<Long> staffIds);
}

interface RateTermRepository extends JpaRepository<RateTerm, Long> {

	List<RateTerm> findByAccountIdOrderByValidFrom(long accountId);
}

interface DueDayTermRepository extends JpaRepository<DueDayTerm, Long> {

	List<DueDayTerm> findByAccountIdOrderByValidFrom(long accountId);
}

interface InstallmentRepository extends JpaRepository<InstallmentRow, Long> {

	List<InstallmentRow> findByAccountIdOrderByScheduleVersionAscSeqAsc(long accountId);

	List<InstallmentRow> findByAccountIdAndScheduleVersionOrderBySeq(long accountId, int scheduleVersion);
}

interface AccountEventRepository extends JpaRepository<AccountEvent, Long> {

	List<AccountEvent> findByAccountIdOrderById(long accountId);

	Optional<AccountEvent> findByPaymentIdAndTypeAndStatus(long paymentId, AccountEvent.Type type, String status);

	List<AccountEvent> findByPaymentIdAndStatus(long paymentId, String status);
}

interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

	@EntityGraph(attributePaths = "allocations")
	List<LedgerEntry> findByAccountIdOrderById(long accountId);

	@Query("select coalesce(sum(e.principal), 0) from LedgerEntry e where e.accountId = :accountId"
			+ " and e.type not in ('DISBURSE', 'ACQUIRE')")
	long sumPrincipalRepaid(@Param("accountId") long accountId);

	@Query("select coalesce(sum(e.suspense), 0) from LedgerEntry e where e.accountId = :accountId")
	long sumSuspense(@Param("accountId") long accountId);

	/**
	 * 처리일 일보의 "정정" (R28): 이 업무일자에 정정번호로 기록된 과거 기산일 현금 유입 거래(가수금 입고·즉시 충당)의
	 * 역분개와 재기록 합계. 재충당으로 생긴 내부 거래(가수금 적용 등)는 세지 않는다.
	 */
	@Query("""
			select coalesce(sum(e.amount), 0) from LedgerEntry e
			where e.businessDate = :date and e.correctionId is not null and e.valueDate < :date
			  and (e.type in ('SUSPENSE_IN', 'REPAYMENT')
			       or (e.type = 'REVERSAL' and exists (select 1 from LedgerEntry o
			            where o.id = e.reversalOfId and o.type in ('SUSPENSE_IN', 'REPAYMENT'))))
			""")
	long sumCorrectionsPostedOn(@Param("date") LocalDate date);
}

interface AccelerationNoticeRepository extends JpaRepository<AccelerationNotice, Long> {

	List<AccelerationNotice> findByAccountIdOrderById(long accountId);
}

interface AcquisitionRepository extends JpaRepository<AcquisitionRow, Long> {

	Optional<AcquisitionRow> findByAccountId(long accountId);
}
