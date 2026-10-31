package dev.arche.servicing.engine;

import dev.arche.common.InterestRate;
import java.time.LocalDate;

/** 금리 이력 한 줄: validFrom부터 다음 이력 전까지 적용 (RateTerm의 엔진용 표현) */
public record RatePeriod(LocalDate validFrom, InterestRate rate) {
}
