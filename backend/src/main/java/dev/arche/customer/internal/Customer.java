package dev.arche.customer.internal;

import dev.arche.common.Masking;
import dev.arche.common.crypto.EncryptedStringConverter;
import dev.arche.common.jpa.BaseEntity;
import dev.arche.customer.CustomerView;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;

@Entity
@Table(name = "customer")
class Customer extends BaseEntity {

	private String customerNo;
	private String name;

	@Convert(converter = EncryptedStringConverter.class)
	@Column(name = "resident_no_enc")
	private String residentNo;

	@Column(name = "resident_no_bidx", columnDefinition = "bpchar(64)")
	private String residentNoBlindIndex;

	private LocalDate birthDate;

	@Convert(converter = EncryptedStringConverter.class)
	@Column(name = "phone_enc")
	private String phone;

	@Column(name = "phone_bidx", columnDefinition = "bpchar(64)")
	private String phoneBlindIndex;

	@Convert(converter = EncryptedStringConverter.class)
	@Column(name = "address_enc")
	private String address;

	protected Customer() {
	}

	Customer(String customerNo, String name, String residentNo, String residentNoBlindIndex, LocalDate birthDate,
			String phone, String phoneBlindIndex, String address) {
		this.customerNo = customerNo;
		this.phoneBlindIndex = phoneBlindIndex;
		this.name = name;
		this.residentNo = residentNo;
		this.residentNoBlindIndex = residentNoBlindIndex;
		this.birthDate = birthDate;
		this.phone = phone;
		this.address = address;
	}

	CustomerView view() {
		return new CustomerView(getId(), customerNo, name, Masking.residentNo(residentNo), birthDate,
				Masking.phone(phone));
	}

	String residentNo() {
		return residentNo;
	}

	/** 주소 원문 (양식·우편용, R72). 화면 목록에는 쓰지 않는다 */
	String address() {
		return address;
	}

	String phone() {
		return phone;
	}

	void indexPhone(String blindIndex) {
		this.phoneBlindIndex = blindIndex;
	}

	/** 개인정보를 출력하지 않는다 */
	@Override
	public String toString() {
		return "Customer[" + customerNo + "]";
	}
}
