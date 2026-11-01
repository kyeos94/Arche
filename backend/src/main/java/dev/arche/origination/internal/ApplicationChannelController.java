package dev.arche.origination.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 신청 상세의 채널 정보: 중개업자·수수료, 플랫폼 한도조회·결과 통보, 본인인증 랜딩 링크 */
@RestController
@RequestMapping("/api/applications/{id}")
@PreAuthorize("hasAuthority('application.review')")
class ApplicationChannelController {

	record BrokerInfo(long id, String brokerNo, String name, String status) {
	}

	record CommissionInfo(String entryType, long baseAmount, BigDecimal rate, long amount, LocalDate occurredOn,
			String reasonCode, String reason) {
	}

	record PlatformInquiryInfo(String inquiryNo, String platformCode, String platformRef, Integer creditGrade,
			boolean offered, Long offeredLimit, BigDecimal offeredRate, LocalDate inquiredOn, LocalDate validUntil) {
	}

	record NoticeInfo(String result, boolean delivered, String receiptId, int attempts, OffsetDateTime lastSentAt) {
	}

	record LandingInfo(long id, String status, OffsetDateTime expiresAt, int failedAttempts, OffsetDateTime verifiedAt,
			OffsetDateTime completedAt, OffsetDateTime createdAt) {
		static LandingInfo of(IdentityVerification v) {
			return new LandingInfo(v.getId(), v.status().name(), v.expiresAt(), v.failedAttempts(), v.verifiedAt(),
					v.completedAt(), v.getCreatedAt());
		}
	}

	record ChannelDto(String channel, BrokerInfo broker, List<CommissionInfo> commissions,
			PlatformInquiryInfo platformInquiry, List<NoticeInfo> platformNotices, List<LandingInfo> landingLinks) {
	}

	/** 발급 결과: 문자로 보낸 링크 (데모에서는 화면에서도 열 수 있게 경로를 돌려준다) */
	record IssuedLink(LandingInfo link, String path, String url, String messageId) {
	}

	private final ApplicationService applications;
	private final BrokerService brokers;
	private final PlatformService platform;
	private final LandingService landing;

	ApplicationChannelController(ApplicationService applications, BrokerService brokers, PlatformService platform,
			LandingService landing) {
		this.applications = applications;
		this.brokers = brokers;
		this.platform = platform;
		this.landing = landing;
	}

	@GetMapping("/channel")
	@Transactional(readOnly = true)
	ChannelDto channel(@PathVariable long id) {
		LoanApplication a = applications.find(id);
		BrokerInfo broker = null;
		if (a.brokerId() != null) {
			Broker b = brokers.find(a.brokerId());
			broker = new BrokerInfo(b.getId(), b.brokerNo(), b.name(), b.status().name());
		}
		PlatformInquiryInfo inquiry = platform.inquiryOf(id).map(q -> new PlatformInquiryInfo(q.inquiryNo(),
				q.platformCode(), q.platformRef(), q.creditGrade(), q.offered(), q.offeredLimit(), q.offeredRate(),
				q.inquiredOn(), q.validUntil())).orElse(null);
		return new ChannelDto(a.channel().name(), broker,
				brokers.forApplication(id).stream().map(c -> new CommissionInfo(c.entryType().name(), c.baseAmount(),
						c.rate(), c.amount(), c.occurredOn(), c.reasonCode() == null ? null : c.reasonCode().name(),
						c.reason())).toList(),
				inquiry,
				platform.notices(id).stream().map(n -> new NoticeInfo(n.result(), n.delivered(), n.receiptId(),
						n.attempts(), n.lastSentAt())).toList(),
				landing.forApplication(id).stream().map(LandingInfo::of).toList());
	}

	@PostMapping("/landing-links")
	IssuedLink issueLanding(@PathVariable long id) {
		LandingService.Issued i = landing.issue(id);
		return new IssuedLink(LandingInfo.of(i.verification()), i.path(), i.url(), i.providerId());
	}

	@PostMapping("/platform-notices/resend")
	List<NoticeInfo> resend(@PathVariable long id) {
		return platform.resend(id).stream().map(n -> new NoticeInfo(n.result(), n.delivered(), n.receiptId(),
				n.attempts(), n.lastSentAt())).toList();
	}
}
