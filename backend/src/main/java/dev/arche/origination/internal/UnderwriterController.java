package dev.arche.origination.internal;

import dev.arche.staff.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 심사자 배정 현황·프로필 (R55), 상품별 징구 서류 목록 (R54) */
@RestController
@RequestMapping("/api")
class UnderwriterController {

	record UnderwriterDto(long staffId, String loginId, String name, String role, List<String> productCodes,
			boolean allProducts, boolean available, int pending, OffsetDateTime lastAssignedAt) {
	}

	record UnderwritersDto(String strategy, List<UnderwriterDto> underwriters) {
	}

	record ProfileRequest(@NotNull List<String> productCodes, @NotNull Boolean available) {
	}

	record RequirementDto(String productCode, String docType, String label, boolean required) {
	}

	private final AssignmentService assignment;
	private final DocumentRequirementRepository requirements;

	UnderwriterController(AssignmentService assignment, DocumentRequirementRepository requirements) {
		this.assignment = assignment;
		this.requirements = requirements;
	}

	@GetMapping("/underwriters")
	@PreAuthorize("hasAuthority('application.review')")
	@Transactional(readOnly = true)
	UnderwritersDto underwriters() {
		return new UnderwritersDto(assignment.strategyCode(),
				assignment.underwriters(List.of(Role.UNDERWRITER, Role.UNDERWRITING_LEAD)).stream()
						.map(u -> new UnderwriterDto(u.staffId(), u.loginId(), u.name(), u.role(), u.productCodes(),
								u.allProducts(), u.available(), u.pending(), u.lastAssignedAt()))
						.toList());
	}

	@PostMapping("/underwriters/{staffId}/profile")
	@PreAuthorize("hasAuthority('application.assign')")
	void profile(@PathVariable long staffId, @Valid @RequestBody ProfileRequest r) {
		assignment.saveProfile(staffId, r.productCodes(), r.available());
	}

	@GetMapping("/document-requirements")
	@Transactional(readOnly = true)
	List<RequirementDto> requirements() {
		return requirements.findAllByOrderByProductCodeAscSortOrderAsc().stream()
				.map(r -> new RequirementDto(r.productCode(), r.docType().name(), r.docType().label, r.required()))
				.toList();
	}
}
