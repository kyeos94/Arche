package dev.arche.servicing.internal;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * 원장 거래 1건이 어느 회차·항목에 얼마 충당됐는지 (guide 16.4). installmentSeq가 null이면 회차 없는 항목.
 */
@Entity
@Immutable
@Table(name = "ledger_allocation")
class LedgerAllocation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	private Integer installmentSeq;
	private String component;
	private long amount;

	protected LedgerAllocation() {
	}

	LedgerAllocation(Integer installmentSeq, String component, long amount) {
		this.installmentSeq = installmentSeq;
		this.component = component;
		this.amount = amount;
	}

	Integer installmentSeq() {
		return installmentSeq;
	}

	String component() {
		return component;
	}

	long amount() {
		return amount;
	}
}
