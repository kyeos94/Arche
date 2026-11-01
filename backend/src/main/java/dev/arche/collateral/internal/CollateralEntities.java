package dev.arche.collateral.internal;

import dev.arche.common.Masking;
import dev.arche.common.Money;
import dev.arche.common.crypto.EncryptedStringConverter;
import dev.arche.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.hibernate.annotations.Immutable;

enum CollateralType {
	VEHICLE, REAL_ESTATE
}

enum LinkStatus {
	ACTIVE, RELEASED
}

/**
 * 담보 (정책서 1장 P3 차량·P4 부동산 근저당). 차량·부동산 공통 필드에 유형별 필드를 둔다.
 * seniorClaim은 선순위 채권액(회사보다 앞선 근저당 등), lienAmount는 회사의 근저당 설정액.
 */
@Entity
@Table(name = "collateral")
class Collateral extends BaseEntity {

	private long accountId;
	@Enumerated(EnumType.STRING)
	private CollateralType type;
	private String description;
	/** 차량번호·등기 고유번호 등 (고객 식별번호가 아님) */
	private String identifier;
	private String vehicleClass;
	private Integer modelYear;
	private String address;
	@Column(name = "area_m2")
	private BigDecimal areaM2;
	private long seniorClaim;
	private long lienAmount;
	private int lienRank;
	@Enumerated(EnumType.STRING)
	private LinkStatus status;
	private LocalDate releasedOn;

	protected Collateral() {
	}

	Collateral(long accountId, CollateralType type, String description, String identifier, String vehicleClass,
			Integer modelYear, String address, BigDecimal areaM2, Money seniorClaim, Money lienAmount, int lienRank) {
		this.accountId = accountId;
		this.type = type;
		this.description = description;
		this.identifier = identifier;
		this.vehicleClass = vehicleClass;
		this.modelYear = modelYear;
		this.address = address;
		this.areaM2 = areaM2;
		this.seniorClaim = seniorClaim.won();
		this.lienAmount = lienAmount.won();
		this.lienRank = lienRank;
		this.status = LinkStatus.ACTIVE;
	}

	void release(LocalDate on) {
		this.status = LinkStatus.RELEASED;
		this.releasedOn = on;
	}

	long accountId() {
		return accountId;
	}

	CollateralType type() {
		return type;
	}

	String description() {
		return description;
	}

	String identifier() {
		return identifier;
	}

	String vehicleClass() {
		return vehicleClass;
	}

	Integer modelYear() {
		return modelYear;
	}

	String address() {
		return address;
	}

	BigDecimal areaM2() {
		return areaM2;
	}

	Money seniorClaim() {
		return Money.won(seniorClaim);
	}

	Money lienAmount() {
		return Money.won(lienAmount);
	}

	int lienRank() {
		return lienRank;
	}

	LinkStatus status() {
		return status;
	}

	LocalDate releasedOn() {
		return releasedOn;
	}
}

/** 담보 시세 조회 이력 (추가 전용). LTV는 가장 최근 값으로 계산한다 (R60) */
@Entity
@Immutable
@Table(name = "collateral_valuation")
class CollateralValuation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	private long collateralId;
	private LocalDate valuedOn;
	private long price;
	private String source;
	private String basis;
	private OffsetDateTime createdAt;
	private String createdBy;

	protected CollateralValuation() {
	}

	CollateralValuation(long collateralId, LocalDate valuedOn, Money price, String source, String basis,
			OffsetDateTime createdAt, String createdBy) {
		this.collateralId = collateralId;
		this.valuedOn = valuedOn;
		this.price = price.won();
		this.source = source;
		this.basis = basis.length() > 200 ? basis.substring(0, 200) : basis;
		this.createdAt = createdAt;
		this.createdBy = createdBy;
	}

	Long getId() {
		return id;
	}

	long collateralId() {
		return collateralId;
	}

	LocalDate valuedOn() {
		return valuedOn;
	}

	Money price() {
		return Money.won(price);
	}

	String source() {
		return source;
	}

	String basis() {
		return basis;
	}
}

/**
 * 보증인. 식별번호·연락처는 고객과 같이 암호문 + 블라인드 인덱스로 저장하고 화면에는 마스킹 값만 보낸다 (guide 10.2·10.3).
 */
@Entity
@Table(name = "guarantor")
class Guarantor extends BaseEntity {

	private long accountId;
	private String name;
	@Convert(converter = EncryptedStringConverter.class)
	@Column(name = "resident_no_enc")
	private String residentNo;
	@Column(name = "resident_no_bidx", columnDefinition = "bpchar(64)")
	private String residentNoBlindIndex;
	@Convert(converter = EncryptedStringConverter.class)
	@Column(name = "phone_enc")
	private String phone;
	private String relation;
	private long guaranteeLimit;
	private LocalDate startDate;
	private LocalDate endDate;
	@Enumerated(EnumType.STRING)
	private LinkStatus status;
	private LocalDate releasedOn;

	protected Guarantor() {
	}

	Guarantor(long accountId, String name, String residentNo, String residentNoBlindIndex, String phone,
			String relation, Money guaranteeLimit, LocalDate startDate, LocalDate endDate) {
		this.accountId = accountId;
		this.name = name;
		this.residentNo = residentNo;
		this.residentNoBlindIndex = residentNoBlindIndex;
		this.phone = phone;
		this.relation = relation;
		this.guaranteeLimit = guaranteeLimit.won();
		this.startDate = startDate;
		this.endDate = endDate;
		this.status = LinkStatus.ACTIVE;
	}

	void release(LocalDate on) {
		this.status = LinkStatus.RELEASED;
		this.releasedOn = on;
	}

	long accountId() {
		return accountId;
	}

	String name() {
		return name;
	}

	String residentNoMasked() {
		return Masking.residentNo(residentNo);
	}

	String phoneMasked() {
		return phone == null ? null : Masking.phone(phone);
	}

	String relation() {
		return relation;
	}

	Money guaranteeLimit() {
		return Money.won(guaranteeLimit);
	}

	LocalDate startDate() {
		return startDate;
	}

	LocalDate endDate() {
		return endDate;
	}

	LinkStatus status() {
		return status;
	}

	LocalDate releasedOn() {
		return releasedOn;
	}

	/** 개인정보를 출력하지 않는다 */
	@Override
	public String toString() {
		return "Guarantor[" + getId() + "]";
	}
}
