package dev.arche.reporting.internal;

import dev.arche.common.Money;
import dev.arche.servicing.ServicingReports.AccountBalance;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 대손충당금 기초 집계 (R67). 순수 함수.
 */
final class Provision {

	private Provision() {
	}

	/** 채권 잔액 = 원금 + 확정·미납 정상이자 + 확정·미납 가산이자 + 미회수 비용 + 양수 전 미수이자 (추정 미수이자 제외, R1) */
	static long claim(AccountBalance a) {
		return a.principalBalance() + a.unpaidNormalInterest() + a.unpaidLateInterest() + a.costBalance()
				+ a.preAcqInterest();
	}

	/** 구간별 계약 1건 (월마감 행 또는 현재 잔액) */
	record Exposure(Bucket bucket, long claim) {
	}

	record Row(Bucket bucket, long count, long claim, BigDecimal rate, long provision) {
	}

	record Result(List<Row> rows, long count, long claim, long provision) {
	}

	/** 구간별 합계 × 적립률, 원 미만 절사. 종결 구간은 넣지 않는다 */
	static Result of(List<Exposure> exposures, Map<Bucket, BigDecimal> rates) {
		Map<Bucket, long[]> sums = new EnumMap<>(Bucket.class);
		for (Bucket b : Bucket.values()) {
			if (b.open()) {
				sums.put(b, new long[2]);
			}
		}
		for (Exposure e : exposures) {
			long[] s = sums.get(e.bucket());
			if (s != null) {
				s[0]++;
				s[1] += e.claim();
			}
		}
		List<Row> rows = new ArrayList<>();
		long count = 0;
		long claim = 0;
		long provision = 0;
		for (Map.Entry<Bucket, long[]> s : sums.entrySet()) {
			BigDecimal rate = rates.getOrDefault(s.getKey(), BigDecimal.ZERO);
			long p = Money.floor(BigDecimal.valueOf(s.getValue()[1]).multiply(rate).movePointLeft(2)).won();
			rows.add(new Row(s.getKey(), s.getValue()[0], s.getValue()[1], rate, p));
			count += s.getValue()[0];
			claim += s.getValue()[1];
			provision += p;
		}
		return new Result(rows, count, claim, provision);
	}
}
