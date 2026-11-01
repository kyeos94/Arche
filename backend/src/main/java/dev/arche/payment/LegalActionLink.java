package dev.arche.payment;

import java.util.Optional;

/**
 * 배당금·공탁금 입금을 연결할 법조치 확인 (R52). collection 모듈이 구현한다.
 * payment가 collection을 직접 참조하면 collection → integration → payment → collection 순환이 생기므로 의존 방향을 뒤집었다.
 */
public interface LegalActionLink {

	/** 법조치가 속한 계약 id. 없으면 empty */
	Optional<Long> accountIdOf(long legalActionId);
}
