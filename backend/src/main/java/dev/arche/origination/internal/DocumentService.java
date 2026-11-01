package dev.arche.origination.internal;

import dev.arche.common.BusinessException;
import dev.arche.integration.StoragePort;
import dev.arche.staff.AccessLogger;
import dev.arche.staff.StaffDirectory;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 서류 징구 (R54): 상품별 필수 서류 체크리스트, 접수(파일·서면 원본)·확인·보완 요청. 파일 본문은 StoragePort에 둔다.
 */
@Service
class DocumentService {

	static final String STORAGE_CATEGORY = "application-documents";
	static final long MAX_BYTES = 10L * 1024 * 1024;

	/** 허용 형식과 파일 첫 바이트(매직 넘버). 확장자·Content-Type만 믿지 않는다 */
	static final Map<String, byte[]> ALLOWED = Map.of(
			"application/pdf", new byte[] { '%', 'P', 'D', 'F' },
			"image/jpeg", new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF },
			"image/png", new byte[] { (byte) 0x89, 'P', 'N', 'G' });

	record FileContent(String fileName, String contentType, byte[] bytes) {
	}

	private final ApplicationDocumentRepository documents;
	private final DocumentRequirementRepository requirements;
	private final StoragePort storage;
	private final StaffDirectory staff;
	private final AccessLogger accessLog;
	private final Clock clock;

	DocumentService(ApplicationDocumentRepository documents, DocumentRequirementRepository requirements,
			StoragePort storage, StaffDirectory staff, AccessLogger accessLog, Clock clock) {
		this.documents = documents;
		this.requirements = requirements;
		this.storage = storage;
		this.staff = staff;
		this.accessLog = accessLog;
		this.clock = clock;
	}

	/** 접수 시 상품의 징구 목록으로 체크리스트를 만든다 */
	@Transactional
	void createChecklist(LoanApplication a) {
		for (DocumentRequirement r : requirements.findByProductCodeOrderBySortOrder(a.productCode())) {
			documents.save(new ApplicationDocument(a.getId(), r.docType(), r.required()));
		}
	}

	List<ApplicationDocument> list(long applicationId) {
		return documents.findByApplicationIdOrderById(applicationId);
	}

	@Transactional
	ApplicationDocument upload(LoanApplication a, long documentId, ApplicationDocument.Via via, String fileName,
			String contentType, byte[] content) {
		requireOpen(a);
		ApplicationDocument d = find(a.getId(), documentId);
		validateFile(contentType, content);
		StoragePort.StoredObject stored = storage.store(STORAGE_CATEGORY, content);
		d.receiveFile(via, stored.key(), safeName(fileName), contentType, stored.size(), stored.sha256(), now());
		return d;
	}

	@Transactional
	ApplicationDocument upload(LoanApplication a, DocumentType type, ApplicationDocument.Via via, String fileName,
			String contentType, byte[] content) {
		ApplicationDocument d = documents.findByApplicationIdAndDocType(a.getId(), type).orElseThrow(
				() -> new BusinessException("DOCUMENT_NOT_FOUND", "이 신청에 징구 대상이 아닌 서류입니다: " + type.label));
		return upload(a, d.getId(), via, fileName, contentType, content);
	}

	@Transactional
	ApplicationDocument receivePaper(LoanApplication a, long documentId, String note) {
		requireOpen(a);
		ApplicationDocument d = find(a.getId(), documentId);
		d.receivePaper(note, now());
		return d;
	}

	@Transactional
	ApplicationDocument verify(LoanApplication a, long documentId) {
		requireOpen(a);
		ApplicationDocument d = find(a.getId(), documentId);
		d.verify(staff.current().loginId(), now());
		return d;
	}

	@Transactional
	ApplicationDocument reject(LoanApplication a, long documentId, String note) {
		requireOpen(a);
		ApplicationDocument d = find(a.getId(), documentId);
		d.reject(staff.current().loginId(), note, now());
		return d;
	}

	/** 신분증 등 개인정보 서류 열람은 접근 기록을 남긴다 (guide 8.2) */
	FileContent file(long applicationId, long documentId) {
		ApplicationDocument d = find(applicationId, documentId);
		if (d.storageKey() == null) {
			throw new BusinessException("FILE_NOT_FOUND", "파일로 받은 서류가 아닙니다");
		}
		accessLog.log("APPLICATION_DOCUMENT_VIEW", "APPLICATION_DOCUMENT", String.valueOf(documentId),
				d.docType().label);
		return new FileContent(d.fileName(), d.contentType(), storage.load(d.storageKey()));
	}

	/** 승인 전 확인되지 않은 필수 서류 (R54) */
	List<DocumentType> missingRequired(long applicationId) {
		return list(applicationId).stream()
				.filter(d -> d.required() && d.status() != ApplicationDocument.Status.VERIFIED)
				.map(ApplicationDocument::docType).toList();
	}

	static void validateFile(String contentType, byte[] content) {
		if (content == null || content.length == 0) {
			throw new BusinessException("FILE_EMPTY", "빈 파일입니다");
		}
		if (content.length > MAX_BYTES) {
			throw new BusinessException("FILE_TOO_LARGE", "파일은 10MB 이하만 받습니다");
		}
		byte[] magic = contentType == null ? null : ALLOWED.get(contentType);
		if (magic == null) {
			throw new BusinessException("FILE_TYPE", "PDF·JPG·PNG 파일만 받습니다");
		}
		for (int i = 0; i < magic.length; i++) {
			if (content.length <= i || content[i] != magic[i]) {
				throw new BusinessException("FILE_TYPE", "파일 내용이 형식(" + contentType + ")과 맞지 않습니다");
			}
		}
	}

	static String safeName(String name) {
		if (name == null || name.isBlank()) {
			return "file";
		}
		String base = name.replace('\\', '/');
		base = base.substring(base.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
		return base.length() > 200 ? base.substring(base.length() - 200) : base;
	}

	private static void requireOpen(LoanApplication a) {
		if (!a.beforeDecision()) {
			throw new BusinessException("APPLICATION_STATE", "승인·거절된 신청의 서류는 바꿀 수 없습니다");
		}
	}

	private ApplicationDocument find(long applicationId, long documentId) {
		return documents.findByIdAndApplicationId(documentId, applicationId)
				.orElseThrow(() -> new BusinessException("DOCUMENT_NOT_FOUND", "서류가 없습니다"));
	}

	private OffsetDateTime now() {
		return OffsetDateTime.now(clock);
	}
}
