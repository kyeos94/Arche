package dev.arche.servicing.internal;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface LoanSaleRepository extends JpaRepository<LoanSale, Long> {

	List<LoanSale> findByAccountIdOrderByIdDesc(long accountId);

	Optional<LoanSale> findFirstByAccountIdAndStatusOrderByIdDesc(long accountId, LoanSale.Status status);
}

interface AdjustmentRequestRepository extends JpaRepository<AdjustmentRequest, Long> {

	List<AdjustmentRequest> findByAccountIdOrderByIdDesc(long accountId);

	List<AdjustmentRequest> findTop200ByStatusOrderByIdDesc(AdjustmentRequest.Status status);

	List<AdjustmentRequest> findTop200ByOrderByIdDesc();

	Optional<AdjustmentRequest> findFirstByAccountIdAndTypeAndStatusOrderById(long accountId,
			AdjustmentRequest.Type type, AdjustmentRequest.Status status);
}
