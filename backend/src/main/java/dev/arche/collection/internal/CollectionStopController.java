package dev.arche.collection.internal;

import dev.arche.common.InterestRate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
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

/**
 * 추심 중지 API (정책서 R45·R46). 조회는 계약 조회 권한, 등록·해제·조정 금리 확정은 채권팀장·본부장(collection.stop).
 */
@RestController
@RequestMapping("/api/loans/{id}/collection-stops")
class CollectionStopController {

	record StopRequest(@NotNull CollectionStop.Reason reason, @NotNull LocalDate effectiveDate,
			@NotBlank String memo) {
	}

	record ReleaseRequest(@NotBlank String reason) {
	}

	record ReliefRequest(@NotNull BigDecimal rate, @NotNull LocalDate validFrom) {
	}

	record StopDto(long id, String groupNo, long accountId, String reason, String reasonLabel,
			boolean suspendsInterest, LocalDate effectiveDate, String memo, String status, LocalDate releasedOn,
			String releaseReason, Long interestEventId, BigDecimal reliefRate, LocalDate reliefFrom,
			String createdBy, OffsetDateTime createdAt) {
		static StopDto of(CollectionStop s) {
			return new StopDto(s.getId(), s.groupNo(), s.accountId(), s.reason().name(), s.reason().label,
					s.reason().suspendsInterest, s.effectiveDate(), s.memo(), s.status().name(), s.releasedOn(),
					s.releaseReason(), s.interestEventId(), s.reliefRate(), s.reliefFrom(), s.getCreatedBy(),
					s.getCreatedAt());
		}
	}

	private final AccountAccess access;
	private final CollectionStopService stops;

	CollectionStopController(AccountAccess access, CollectionStopService stops) {
		this.access = access;
		this.stops = stops;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('loan.read')")
	@Transactional(readOnly = true)
	List<StopDto> list(@PathVariable long id) {
		access.visible(id);
		return stops.of(id).stream().map(StopDto::of).toList();
	}

	/** 그 고객의 열린 계약 전체에 걸린 행을 돌려준다 */
	@PostMapping
	@PreAuthorize("hasAuthority('collection.stop')")
	List<StopDto> register(@PathVariable long id, @Valid @RequestBody StopRequest body) {
		access.visible(id);
		return stops.register(id, body.reason(), body.effectiveDate(), body.memo()).stream().map(StopDto::of)
				.toList();
	}

	@PostMapping("/{stopId}/release")
	@PreAuthorize("hasAuthority('collection.stop')")
	List<StopDto> release(@PathVariable long id, @PathVariable long stopId, @Valid @RequestBody ReleaseRequest body) {
		access.visible(id);
		return stops.release(id, stopId, body.reason()).stream().map(StopDto::of).toList();
	}

	@PostMapping("/{stopId}/confirm-relief")
	@PreAuthorize("hasAuthority('collection.stop')")
	StopDto confirmRelief(@PathVariable long id, @PathVariable long stopId, @Valid @RequestBody ReliefRequest body) {
		access.visible(id);
		return StopDto.of(stops.confirmRelief(id, stopId, new InterestRate(body.rate()), body.validFrom()));
	}
}
