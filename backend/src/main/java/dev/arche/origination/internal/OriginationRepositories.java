package dev.arche.origination.internal;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface ApplicationConsentRepository extends JpaRepository<ApplicationConsent, Long> {

	List<ApplicationConsent> findByApplicationIdOrderById(long applicationId);

	List<ApplicationConsent> findByApplicationIdAndConsentTypeOrderByIdDesc(long applicationId,
			ApplicationConsent.Type type);
}

interface CreditInquiryRepository extends JpaRepository<CreditInquiry, Long> {

	List<CreditInquiry> findByApplicationIdOrderByIdDesc(long applicationId);
}

interface ScreeningResultRepository extends JpaRepository<ScreeningResult, Long> {

	List<ScreeningResult> findByApplicationIdOrderById(long applicationId);
}

interface DocumentRequirementRepository extends JpaRepository<DocumentRequirement, Long> {

	List<DocumentRequirement> findByProductCodeOrderBySortOrder(String productCode);

	List<DocumentRequirement> findAllByOrderByProductCodeAscSortOrderAsc();
}

interface ApplicationDocumentRepository extends JpaRepository<ApplicationDocument, Long> {

	List<ApplicationDocument> findByApplicationIdOrderById(long applicationId);

	Optional<ApplicationDocument> findByIdAndApplicationId(long id, long applicationId);

	Optional<ApplicationDocument> findByApplicationIdAndDocType(long applicationId, DocumentType docType);
}

interface UnderwriterProfileRepository extends JpaRepository<UnderwriterProfile, Long> {
}
