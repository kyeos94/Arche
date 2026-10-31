/**
 * 순수 계산 엔진. 계약 조건과 이벤트 목록을 받아 기준일까지 하루씩 재생(replay)해
 * 스케줄·원장 거래·연체·기한이익상실 상태를 만든다. DB·Spring에 의존하지 않는다.
 * 같은 입력이면 항상 같은 결과를 낸다 (소급 입금·취소 재계산의 전제).
 */
@org.jspecify.annotations.NullMarked
package dev.arche.servicing.engine;
