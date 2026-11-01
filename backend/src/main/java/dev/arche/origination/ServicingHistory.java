package dev.arche.origination;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 심사가 필요로 하는 여신관리 정보 (내부 불량 이력 R53, 조기상환 수수료 환수 R56).
 * <p>servicing이 실행 이벤트로 origination을 이미 참조하므로 origination → servicing 의존은 모듈 순환이 된다.
 * 그래서 심사 쪽이 필요한 질의만 이 인터페이스로 선언하고, 두 모듈을 모두 아는 operation 모듈이 구현한다 (ADR 0007).
 */
public interface ServicingHistory {

	/** 고객의 계약 중 불량 상태(연체·기한이익상실·채무조정·상각·매각)인 것의 "계약번호 상태" 목록. 없으면 빈 목록 */
	List<String> adverseAccounts(long customerId);

	/** 신청으로 만들어진 계약의 완제일. 계약이 없거나 완제 전이면 empty */
	Optional<LocalDate> paidOffOn(long applicationId);
}
