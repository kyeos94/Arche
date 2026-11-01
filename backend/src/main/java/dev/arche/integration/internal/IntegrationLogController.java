package dev.arche.integration.internal;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 연계 요청 이력 조회 (운영 관리). 요약은 저장 시 이미 마스킹되어 있다 */
@RestController
@RequestMapping("/api/integration/logs")
class IntegrationLogController {

	record Row(long id, String institution, String operation, String summary, long elapsedMs, String resultCode,
			boolean success, OffsetDateTime calledAt) {
	}

	record PageResponse(List<Row> content, int page, int size, long totalElements, int totalPages) {
	}

	private final IntegrationCallLogRepository logs;

	IntegrationLogController(IntegrationCallLogRepository logs) {
		this.logs = logs;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('admin.manage')")
	@Transactional(readOnly = true)
	PageResponse list(@RequestParam(defaultValue = "") String institution, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size) {
		Page<IntegrationCallLog> p = logs.findByInstitutionContainingOrderByIdDesc(institution,
				PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200)));
		return new PageResponse(p.getContent().stream().map(l -> new Row(l.getId(), l.institution(), l.operation(),
				l.summary(), l.elapsedMs(), l.resultCode(), l.success(), l.calledAt())).toList(), p.getNumber(),
				p.getSize(), p.getTotalElements(), p.getTotalPages());
	}
}
