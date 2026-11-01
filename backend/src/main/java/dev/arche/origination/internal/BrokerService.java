package dev.arche.origination.internal;

import dev.arche.calendar.BusinessDates;
import dev.arche.common.BusinessException;
import dev.arche.common.Money;
import dev.arche.common.jpa.NumberGenerator;
import dev.arche.origination.ServicingHistory;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 중개업자 관리와 중개수수료 정산·환수 (guide 2.3, R56).
 */
@Service
class BrokerService {

	private static final Logger log = LoggerFactory.getLogger(BrokerService.class);
	private static final SecureRandom RANDOM = new SecureRandom();

	/** 발급 결과. apiKey 원문은 이때 한 번만 보여준다 */
	record Issued(Broker broker, String apiKey) {

		@Override
		public String toString() {
			return "Issued[" + broker.brokerNo() + "]";
		}
	}

	private final BrokerRepository brokers;
	private final BrokerCommissionRateRepository rates;
	private final BrokerCommissionRepository commissions;
	private final ServicingHistory servicing;
	private final BusinessDates businessDates;
	private final NumberGenerator numbers;

	BrokerService(BrokerRepository brokers, BrokerCommissionRateRepository rates,
			BrokerCommissionRepository commissions,
			ServicingHistory servicing, BusinessDates businessDates, NumberGenerator numbers) {
		this.brokers = brokers;
		this.rates = rates;
		this.commissions = commissions;
		this.servicing = servicing;
		this.businessDates = businessDates;
		this.numbers = numbers;
	}

	// ------------------------------------------------------------------ 중개업자

	@Transactional
	Issued register(String name, String registrationNo, String allowedIps, BigDecimal rate) {
		if (name == null || name.isBlank() || registrationNo == null || registrationNo.isBlank()) {
			throw new BusinessException("INVALID_INPUT", "이름과 등록 번호를 입력하세요");
		}
		CommissionRules.validateRate(rate);
		String key = newKey();
		Broker b;
		try {
			b = brokers.saveAndFlush(new Broker(numbers.next("broker_no_seq", "B"), name.trim(), registrationNo.trim(),
					hash(key), allowedIps));
		} catch (DataIntegrityViolationException e) {
			throw new BusinessException("DUPLICATE", "이미 등록된 등록 번호입니다");
		}
		rates.save(new BrokerCommissionRate(b.getId(), rate, businessDates.today(), "등록"));
		return new Issued(b, key);
	}

	@Transactional
	Issued rotateKey(long brokerId) {
		Broker b = find(brokerId);
		String key = newKey();
		b.rotateKey(hash(key));
		return new Issued(b, key);
	}

	@Transactional
	Broker update(long brokerId, Broker.Status status, String allowedIps) {
		Broker b = find(brokerId);
		if (status != null) {
			b.changeStatus(status);
		}
		b.changeAllowedIps(allowedIps);
		return b;
	}

	/**
	 * 수수료율 변경: 새 이율은 업무일자 이후(당일 포함)부터, 기존 마지막 이력보다 뒤에서만 시작한다 (소급 변경 금지).
	 * 이전 이력은 새 시작일에서 닫는다 [from, to)
	 */
	@Transactional
	BrokerCommissionRate changeRate(long brokerId, BigDecimal rate, LocalDate validFrom, String reason) {
		find(brokerId);
		CommissionRules.validateRate(rate);
		if (reason == null || reason.isBlank()) {
			throw new BusinessException("REASON_REQUIRED", "변경 사유를 입력하세요");
		}
		if (validFrom.isBefore(businessDates.today())) {
			throw new BusinessException("RATE_RETROACTIVE", "수수료율은 소급해 바꿀 수 없습니다 (업무일자 이후부터)");
		}
		List<BrokerCommissionRate> terms = rates.findByBrokerIdOrderByValidFrom(brokerId);
		BrokerCommissionRate last = terms.isEmpty() ? null : terms.getLast();
		if (last != null && !validFrom.isAfter(last.validFrom())) {
			throw new BusinessException("RATE_ORDER", "새 수수료율은 " + last.validFrom() + " 이후부터 시작해야 합니다");
		}
		if (last != null) {
			last.closeAt(validFrom);
			rates.saveAndFlush(last);
		}
		return rates.save(new BrokerCommissionRate(brokerId, rate, validFrom, reason));
	}

	/** API 키 + IP로 중개업자 인증. 정지된 중개업자는 접수할 수 없다 */
	Optional<Broker> authenticate(String apiKey, String ip) {
		if (apiKey == null || apiKey.isBlank()) {
			return Optional.empty();
		}
		return brokers.findByApiKeyHash(hash(apiKey))
				.filter(b -> b.status() == Broker.Status.ACTIVE && b.allows(ip));
	}

	Broker find(long id) {
		return brokers.findById(id).orElseThrow(() -> new BusinessException("BROKER_NOT_FOUND", "중개업자가 없습니다"));
	}

	List<Broker> all() {
		return brokers.findAllByOrderById();
	}

	List<BrokerCommissionRate> rates(long brokerId) {
		return rates.findByBrokerIdOrderByValidFrom(brokerId);
	}

	Optional<BigDecimal> currentRate(long brokerId) {
		return CommissionRules.rateOn(rates(brokerId), businessDates.today());
	}

	List<BrokerCommission> commissions(Long brokerId) {
		return brokerId == null ? commissions.findAllByOrderByIdDesc()
				: commissions.findByBrokerIdOrderByIdDesc(brokerId);
	}

	// ------------------------------------------------------------------ 정산·환수 (R56)

	/** 실행 시 정산 확정 (같은 신청은 한 번만). 그날 유효한 수수료율이 없으면 0원으로 남긴다 */
	@Transactional
	void settle(LoanApplication a, LocalDate disbursedOn) {
		if (a.brokerId() == null || has(a.getId(), BrokerCommission.EntryType.SETTLEMENT)) {
			return;
		}
		BigDecimal rate = CommissionRules.rateOn(rates(a.brokerId()), disbursedOn).orElse(BigDecimal.ZERO);
		Money amount = CommissionRules.commission(Money.won(a.approvedAmount()), rate);
		commissions.save(new BrokerCommission(a.brokerId(), a.getId(), BrokerCommission.EntryType.SETTLEMENT,
				a.approvedAmount(), rate, amount.won(), disbursedOn, null, null));
	}

	/** 불완전판매 확인에 따른 환수 (broker.manage, 실행일부터 1년 안) */
	@Transactional
	BrokerCommission clawbackMisSelling(long applicationId, String reason) {
		if (reason == null || reason.isBlank()) {
			throw new BusinessException("REASON_REQUIRED", "불완전판매 확인 내용을 입력하세요");
		}
		BrokerCommission settlement = settlementOf(applicationId).orElseThrow(
				() -> new BusinessException("COMMISSION_NOT_FOUND", "정산된 중개수수료가 없습니다"));
		LocalDate today = businessDates.today();
		if (!CommissionRules.misSellingClawbackAllowed(settlement.occurredOn(), today)) {
			throw new BusinessException("CLAWBACK_PERIOD", "불완전판매 환수는 실행일부터 1년 안에만 할 수 있습니다");
		}
		return clawback(settlement, BrokerCommission.ReasonCode.MIS_SELLING, reason, today);
	}

	/** 조기 완제 환수 점검: 최근 1년 정산 중 실행일부터 3개월 안에 완제된 것 */
	@Transactional
	int scanEarlyPayoff() {
		LocalDate today = businessDates.today();
		int count = 0;
		for (BrokerCommission s : commissions.findByEntryTypeAndOccurredOnGreaterThanEqual(
				BrokerCommission.EntryType.SETTLEMENT, today.minusYears(1))) {
			if (has(s.applicationId(), BrokerCommission.EntryType.CLAWBACK)) {
				continue;
			}
			Optional<LocalDate> paidOff = servicing.paidOffOn(s.applicationId());
			if (paidOff.isPresent() && CommissionRules.earlyPayoffClawback(s.occurredOn(), paidOff.get())) {
				clawback(s, BrokerCommission.ReasonCode.EARLY_PAYOFF, "실행 " + s.occurredOn() + " · 완제 "
						+ paidOff.get() + " (3개월 안)", today);
				count++;
			}
		}
		return count;
	}

	/** 매일 23:30 (일마감 뒤) 조기 완제 환수 점검. 스케줄은 arche.scheduling.enabled일 때만 */
	@Scheduled(cron = "${arche.origination.clawback-cron:0 30 23 * * *}", zone = "Asia/Seoul")
	@SchedulerLock(name = "brokerClawbackScan")
	@Transactional
	public void scheduledScan() {
		int n = scanEarlyPayoff();
		if (n > 0) {
			log.info("중개수수료 조기 완제 환수 {}건", n);
		}
	}

	/** 신청별 중개수수료 (신청 상세 화면) */
	List<BrokerCommission> forApplication(long applicationId) {
		return commissions.findByApplicationIdOrderById(applicationId);
	}

	private BrokerCommission clawback(BrokerCommission s, BrokerCommission.ReasonCode code, String reason,
			LocalDate on) {
		if (has(s.applicationId(), BrokerCommission.EntryType.CLAWBACK)) {
			throw new BusinessException("DUPLICATE", "이미 환수했습니다");
		}
		return commissions.save(new BrokerCommission(s.brokerId(), s.applicationId(),
				BrokerCommission.EntryType.CLAWBACK, s.baseAmount(), s.rate(), -s.amount(), on, code,
				reason.length() > 200 ? reason.substring(0, 200) : reason));
	}

	private Optional<BrokerCommission> settlementOf(long applicationId) {
		return commissions.findByApplicationIdOrderById(applicationId).stream()
				.filter(c -> c.entryType() == BrokerCommission.EntryType.SETTLEMENT).findFirst();
	}

	private boolean has(long applicationId, BrokerCommission.EntryType type) {
		return commissions.findByApplicationIdOrderById(applicationId).stream().anyMatch(c -> c.entryType() == type);
	}

	static String newKey() {
		byte[] b = new byte[24];
		RANDOM.nextBytes(b);
		return "brk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
	}

	static String hash(String s) {
		try {
			return HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
