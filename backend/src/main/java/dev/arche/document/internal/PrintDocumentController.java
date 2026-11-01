package dev.arche.document.internal;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 양식 인쇄 (R72): 계약 조회 + 양식 인쇄 권한, 데이터 범위는 계약 조회와 같음 */
@RestController
@RequestMapping("/api/documents")
@PreAuthorize("hasAuthority('loan.read') and hasAuthority('document.print')")
class PrintDocumentController {

	record RenderRequest(String templateCode, List<Long> accountIds, PrintDocumentService.Purpose purpose) {
	}

	private final PrintDocumentService documents;

	PrintDocumentController(PrintDocumentService documents) {
		this.documents = documents;
	}

	@GetMapping("/templates")
	List<PrintDocumentService.TemplateInfo> templates() {
		return documents.templates();
	}

	@GetMapping("/placeholders")
	List<PrintDocumentService.Placeholder> placeholders() {
		return documents.placeholders();
	}

	/** 미리보기(PREVIEW)는 이력을 남기지 않고, 인쇄(PRINT)는 문서번호를 매겨 인쇄 이력을 남긴다 */
	@PostMapping("/render")
	PrintDocumentService.Result render(@RequestBody RenderRequest body) {
		return documents.render(body.templateCode(), body.accountIds(),
				body.purpose() == null ? PrintDocumentService.Purpose.PREVIEW : body.purpose());
	}

	@GetMapping("/print-log/{accountId}")
	List<PrintDocumentService.PrintLogRow> printLog(@PathVariable long accountId) {
		return documents.printLog(accountId);
	}
}
