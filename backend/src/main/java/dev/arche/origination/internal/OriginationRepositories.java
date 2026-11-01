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

interface BrokerRepository extends JpaRepository<Broker, Long> {

	Optional<Broker> findByApiKeyHash(String apiKeyHash);

	List<Broker> findAllByOrderById();
}

interface BrokerCommissionRateRepository extends JpaRepository<BrokerCommissionRate, Long> {

	List<BrokerCommissionRate> findByBrokerIdOrderByValidFrom(long brokerId);
}

interface BrokerCommissionRepository extends JpaRepository<BrokerCommission, Long> {

	List<BrokerCommission> findByBrokerIdOrderByIdDesc(long brokerId);

	List<BrokerCommission> findAllByOrderByIdDesc();

	List<BrokerCommission> findByApplicationIdOrderById(long applicationId);

	List<BrokerCommission> findByEntryTypeAndOccurredOnGreaterThanEqual(BrokerCommission.EntryType type,
			java.time.LocalDate from);
}

interface PlatformInquiryRepository extends JpaRepository<PlatformInquiry, Long> {

	Optional<PlatformInquiry> findByPlatformCodeAndPlatformRef(String platformCode, String platformRef);

	Optional<PlatformInquiry> findByApplicationId(long applicationId);
}

interface PlatformResultNoticeRepository extends JpaRepository<PlatformResultNotice, Long> {

	Optional<PlatformResultNotice> findByApplicationIdAndResult(long applicationId, String result);

	List<PlatformResultNotice> findByApplicationIdOrderById(long applicationId);
}

interface IdentityVerificationRepository extends JpaRepository<IdentityVerification, Long> {

	Optional<IdentityVerification> findByTokenHash(String tokenHash);

	List<IdentityVerification> findByApplicationIdOrderByIdDesc(long applicationId);
}
