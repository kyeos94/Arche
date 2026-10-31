package dev.arche.servicing;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** 다른 모듈에 노출하는 계약 요약 */
public record AccountRef(long id, String accountNo, String status, String repaymentMethod,
		@Nullable Long assignedStaffId, @Nullable LocalDate paidOffOn, long principalBalance, long payoffAmount) {
}
