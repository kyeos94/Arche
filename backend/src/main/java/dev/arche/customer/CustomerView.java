package dev.arche.customer;

import java.time.LocalDate;

/** 마스킹된 고객 정보 */
public record CustomerView(long id, String customerNo, String name, String residentNoMasked, LocalDate birthDate,
		String phoneMasked) {
}
