package dev.arche.origination.internal;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * 심사자 배정 전략 (guide 2.2: 배정 전략을 인터페이스로 분리). 후보는 이미 담당 상품·부재 여부로 거른 목록이다 (R55).
 * 설정 {@code arche.origination.assignment-strategy}로 고른다. 정책서 7장 기본값은 처리 건수 균등(least-loaded).
 */
interface AssignmentStrategy {

	/**
	 * @param pending        미처리 건수 (ASSIGNED + UNDERWRITING)
	 * @param lastAssignedAt 마지막 배정 시각 (배정 이력이 없으면 null = 가장 오래된 것으로 봄)
	 */
	record Candidate(long staffId, String name, int pending, @Nullable OffsetDateTime lastAssignedAt) {
	}

	String code();

	Optional<Candidate> choose(List<Candidate> candidates);

	static AssignmentStrategy of(String code) {
		return switch (code) {
			case "least-loaded" -> new LeastLoaded();
			case "round-robin" -> new RoundRobin();
			default -> throw new IllegalArgumentException("알 수 없는 배정 전략: " + code);
		};
	}

	/** 미처리 건수가 가장 적은 사람, 동률이면 마지막 배정이 오래된 순(이력 없음이 먼저), 그래도 같으면 직원 ID 순 */
	final class LeastLoaded implements AssignmentStrategy {

		static final Comparator<Candidate> ORDER = Comparator.comparingInt(Candidate::pending)
				.thenComparing(Candidate::lastAssignedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
				.thenComparingLong(Candidate::staffId);

		@Override
		public String code() {
			return "least-loaded";
		}

		@Override
		public Optional<Candidate> choose(List<Candidate> candidates) {
			return candidates.stream().min(ORDER);
		}
	}

	/** 직원 ID 순으로 돌아가며 배정: 가장 최근에 배정받은 사람의 다음 사람 (끝이면 처음으로) */
	final class RoundRobin implements AssignmentStrategy {

		@Override
		public String code() {
			return "round-robin";
		}

		@Override
		public Optional<Candidate> choose(List<Candidate> candidates) {
			List<Candidate> sorted = candidates.stream().sorted(Comparator.comparingLong(Candidate::staffId)).toList();
			if (sorted.isEmpty()) {
				return Optional.empty();
			}
			Optional<Candidate> last = sorted.stream().filter(c -> c.lastAssignedAt() != null)
					.max(Comparator.comparing(Candidate::lastAssignedAt));
			if (last.isEmpty()) {
				return Optional.of(sorted.getFirst());
			}
			long lastId = last.get().staffId();
			return Optional.of(sorted.stream().filter(c -> c.staffId() > lastId).findFirst().orElse(sorted.getFirst()));
		}
	}
}
