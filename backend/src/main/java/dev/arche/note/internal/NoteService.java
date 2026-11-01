package dev.arche.note.internal;

import dev.arche.common.BusinessException;
import dev.arche.staff.CurrentStaff;
import dev.arche.staff.StaffDirectory;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 쪽지 (R74, 가정·검수 필요).
 * <ul>
 * <li>받는 사람은 직원 또는 팀(그 팀의 로그인 가능한 직원 전원). 자기 자신은 빠지고, 한 번에 200명까지</li>
 * <li>제목 1~100자, 본문 1~2,000자. 주민등록번호 형식(6자리-7자리)이 들어 있으면 보내지 않는다 (CLAUDE.md 식별번호 규칙)</li>
 * <li>보낸 뒤 고칠 수 없다. 지우기는 각자의 쪽지함에서 숨기는 것뿐이고 기록은 남는다</li>
 * <li>받은 사람이 처음 열면 읽음. 보낸 사람은 받는 사람별 읽음 여부를 볼 수 있다</li>
 * <li>계약·신청을 붙이면 누를 때 그 화면으로 간다. 그 화면의 조회 권한·데이터 범위는 그대로 적용된다</li>
 * </ul>
 */
@Service
class NoteService {

	static final int MAX_RECIPIENTS = 200;
	static final int LIST_LIMIT = 200;
	/** 주민등록번호 형식: 앞 6자리 + (구분자) + 성별 1~8로 시작하는 7자리 */
	static final Pattern RESIDENT_NO = Pattern.compile("(?<!\\d)\\d{6}\\s*[-–]?\\s*[1-8]\\d{6}(?!\\d)");

	enum RefType {
		LOAN, APPLICATION
	}

	record Ref(RefType type, long id, String label) {
	}

	record SendCommand(List<Long> staffIds, List<Long> teamIds, String subject, String body, @Nullable Ref ref) {
	}

	record InboxItem(long id, @Nullable String sender, String subject, @Nullable Ref ref, OffsetDateTime sentAt,
			@Nullable OffsetDateTime readAt) {
	}

	record SentItem(long id, String subject, String recipients, @Nullable Ref ref, OffsetDateTime sentAt,
			int recipientCount, int readCount) {
	}

	record RecipientStatus(String name, @Nullable String team, @Nullable OffsetDateTime readAt) {
	}

	record Detail(long id, @Nullable Long senderId, @Nullable String sender, String subject, String body,
			@Nullable Ref ref, String recipientSummary, OffsetDateTime sentAt, boolean mine,
			@Nullable List<RecipientStatus> recipients) {
	}

	record Person(long id, String name, String role, @Nullable Long teamId, @Nullable String team) {
	}

	record Team(long id, String name, int members) {
	}

	record Directory(List<Person> staff, List<Team> teams) {
	}

	private final JdbcClient jdbc;
	private final StaffDirectory staff;
	private final Clock clock;

	NoteService(JdbcClient jdbc, StaffDirectory staff, Clock clock) {
		this.jdbc = jdbc;
		this.staff = staff;
		this.clock = clock;
	}

	/** 받는 사람 고르기: 로그인 가능한 직원(나 제외)과 팀 */
	@Transactional(readOnly = true)
	Directory directory() {
		long me = staff.current().id();
		Map<Long, String> teams = staff.teamNames();
		List<CurrentStaff> active = staff.active();
		List<Person> people = active.stream().filter(s -> s.id() != me)
				.map(s -> new Person(s.id(), s.name(), s.role().name(), s.teamId(),
						s.teamId() == null ? null : teams.get(s.teamId())))
				.toList();
		List<Team> teamList = teams.entrySet().stream()
				.map(e -> new Team(e.getKey(), e.getValue(),
						(int) people.stream().filter(p -> e.getKey().equals(p.teamId())).count()))
				.filter(t -> t.members() > 0).toList();
		return new Directory(people, teamList);
	}

	@Transactional
	long send(SendCommand c) {
		CurrentStaff me = staff.current();
		String subject = text(c.subject(), 100, "제목");
		String body = text(c.body(), 2000, "본문");
		validateRef(c.ref());
		Map<Long, String> teams = staff.teamNames();
		Map<Long, CurrentStaff> active = new HashMap<>();
		staff.active().forEach(s -> active.put(s.id(), s));
		Set<Long> to = new LinkedHashSet<>();
		List<String> summary = new ArrayList<>();
		for (Long teamId : c.teamIds() == null ? List.<Long>of() : c.teamIds()) {
			if (!teams.containsKey(teamId)) {
				throw new BusinessException("NOTE_RECIPIENT", "없는 팀입니다");
			}
			active.values().stream().filter(s -> teamId.equals(s.teamId())).map(CurrentStaff::id).forEach(to::add);
			summary.add(teams.get(teamId));
		}
		List<String> names = new ArrayList<>();
		for (Long id : c.staffIds() == null ? List.<Long>of() : c.staffIds()) {
			CurrentStaff s = active.get(id);
			if (s == null) {
				throw new BusinessException("NOTE_RECIPIENT", "받을 수 없는 직원입니다 (퇴사·잠김)");
			}
			to.add(id);
			names.add(s.name());
		}
		to.remove(me.id());
		if (to.isEmpty()) {
			throw new BusinessException("NOTE_RECIPIENT", "받는 사람을 고르세요 (자기 자신에게는 보낼 수 없습니다)");
		}
		if (to.size() > MAX_RECIPIENTS) {
			throw new BusinessException("NOTE_RECIPIENT", "한 번에 " + MAX_RECIPIENTS + "명까지 보낼 수 있습니다");
		}
		if (!names.isEmpty()) {
			summary.add(names.size() <= 2 ? String.join(", ", names) : names.get(0) + " 외 " + (names.size() - 1) + "명");
		}
		return insert(me.id(), subject, body, c.ref(), String.join(" · ", summary) + " (" + to.size() + "명)", to);
	}

	/** 시스템 쪽지 (보낸 사람 없음). 받는 사람이 없으면 보내지 않는다 */
	@Transactional
	void sendSystem(Collection<Long> staffIds, String subject, String body, @Nullable Ref ref) {
		Set<Long> to = new LinkedHashSet<>(staffIds);
		if (to.isEmpty()) {
			return;
		}
		insert(null, subject, body, ref, "시스템 알림 (" + to.size() + "명)", to);
	}

	private long insert(@Nullable Long senderId, String subject, String body, @Nullable Ref ref, String summary,
			Set<Long> to) {
		long id = jdbc.sql("""
				insert into note (sender_id, subject, body, ref_type, ref_id, ref_label, recipient_summary, sent_at)
				values (:s, :subject, :body, :rt, :rid, :rl, :summary, :at) returning id
				""").param("s", senderId).param("subject", subject).param("body", body)
				.param("rt", ref == null ? null : ref.type().name()).param("rid", ref == null ? null : ref.id())
				.param("rl", ref == null ? null : ref.label()).param("summary", truncate(summary, 200))
				.param("at", OffsetDateTime.now(clock)).query(Long.class).single();
		for (Long staffId : to) {
			jdbc.sql("insert into note_recipient (note_id, staff_id) values (:n, :s)").param("n", id)
					.param("s", staffId).update();
		}
		return id;
	}

	@Transactional(readOnly = true)
	List<InboxItem> inbox(boolean unreadOnly) {
		long me = staff.current().id();
		Map<Long, String> names = new HashMap<>();
		return jdbc.sql("""
				select n.id, n.sender_id, n.subject, n.ref_type, n.ref_id, n.ref_label, n.sent_at, r.read_at
				from note_recipient r join note n on n.id = r.note_id
				where r.staff_id = :me and r.hidden_at is null and (:unread = false or r.read_at is null)
				order by n.id desc limit :limit
				""").param("me", me).param("unread", unreadOnly).param("limit", LIST_LIMIT)
				.query((rs, i) -> new InboxItem(rs.getLong("id"), senderName(rs.getObject("sender_id", Long.class), names),
						rs.getString("subject"), ref(rs.getString("ref_type"), rs.getObject("ref_id", Long.class),
								rs.getString("ref_label")),
						rs.getObject("sent_at", OffsetDateTime.class), rs.getObject("read_at", OffsetDateTime.class)))
				.list();
	}

	@Transactional(readOnly = true)
	List<SentItem> sent() {
		long me = staff.current().id();
		return jdbc.sql("""
				select n.id, n.subject, n.recipient_summary, n.ref_type, n.ref_id, n.ref_label, n.sent_at,
				       count(r.id) as total, count(r.read_at) as read
				from note n join note_recipient r on r.note_id = n.id
				where n.sender_id = :me and n.sender_hidden_at is null
				group by n.id order by n.id desc limit :limit
				""").param("me", me).param("limit", LIST_LIMIT)
				.query((rs, i) -> new SentItem(rs.getLong("id"), rs.getString("subject"),
						rs.getString("recipient_summary"),
						ref(rs.getString("ref_type"), rs.getObject("ref_id", Long.class), rs.getString("ref_label")),
						rs.getObject("sent_at", OffsetDateTime.class), rs.getInt("total"), rs.getInt("read")))
				.list();
	}

	@Transactional(readOnly = true)
	int unreadCount() {
		return jdbc.sql("""
				select count(*) from note_recipient where staff_id = :me and read_at is null and hidden_at is null
				""").param("me", staff.current().id()).query(Integer.class).single();
	}

	/** 쪽지 열기. 받은 사람이 처음 열면 읽음으로 표시한다. 보낸 사람·받은 사람만 볼 수 있다 */
	@Transactional
	Detail open(long id) {
		long me = staff.current().id();
		Row n = row(id);
		boolean mine = n.senderId() != null && n.senderId() == me;
		boolean recipient = jdbc.sql("select count(*) from note_recipient where note_id = :n and staff_id = :me")
				.param("n", id).param("me", me).query(Integer.class).single() > 0;
		if (!mine && !recipient) {
			throw new BusinessException("NOTE_NOT_FOUND", "쪽지가 없습니다");
		}
		if (recipient) {
			jdbc.sql("update note_recipient set read_at = :at where note_id = :n and staff_id = :me and read_at is null")
					.param("at", OffsetDateTime.now(clock)).param("n", id).param("me", me).update();
		}
		List<RecipientStatus> statuses = null;
		if (mine) {
			Map<Long, String> teams = staff.teamNames();
			statuses = jdbc.sql("select staff_id, read_at from note_recipient where note_id = :n order by id")
					.param("n", id)
					.query((rs, i) -> {
						var s = staff.byId(rs.getLong("staff_id"));
						return new RecipientStatus(s.map(CurrentStaff::name).orElse("#" + rs.getLong("staff_id")),
								s.map(CurrentStaff::teamId).map(teams::get).orElse(null),
								rs.getObject("read_at", OffsetDateTime.class));
					}).list();
		}
		return new Detail(id, n.senderId(), senderName(n.senderId(), new HashMap<>()), n.subject(), n.body(), n.ref(),
				n.summary(), n.sentAt(), mine, statuses);
	}

	/** 내 쪽지함에서 숨기기 (받은 쪽지면 받은 쪽지함, 내가 보낸 쪽지면 보낸 쪽지함) */
	@Transactional
	void hide(long id) {
		long me = staff.current().id();
		OffsetDateTime now = OffsetDateTime.now(clock);
		int changed = jdbc.sql("update note_recipient set hidden_at = :at where note_id = :n and staff_id = :me"
				+ " and hidden_at is null").param("at", now).param("n", id).param("me", me).update();
		changed += jdbc.sql("update note set sender_hidden_at = :at where id = :n and sender_id = :me"
				+ " and sender_hidden_at is null").param("at", now).param("n", id).param("me", me).update();
		if (changed == 0) {
			throw new BusinessException("NOTE_NOT_FOUND", "쪽지가 없습니다");
		}
	}

	private record Row(@Nullable Long senderId, String subject, String body, @Nullable Ref ref, String summary,
			OffsetDateTime sentAt) {
	}

	private Row row(long id) {
		return jdbc.sql("""
				select sender_id, subject, body, ref_type, ref_id, ref_label, recipient_summary, sent_at
				from note where id = :id
				""").param("id", id)
				.query((rs, i) -> new Row(rs.getObject("sender_id", Long.class), rs.getString("subject"),
						rs.getString("body"),
						ref(rs.getString("ref_type"), rs.getObject("ref_id", Long.class), rs.getString("ref_label")),
						rs.getString("recipient_summary"), rs.getObject("sent_at", OffsetDateTime.class)))
				.optional().orElseThrow(() -> new BusinessException("NOTE_NOT_FOUND", "쪽지가 없습니다"));
	}

	private @Nullable String senderName(@Nullable Long senderId, Map<Long, String> cache) {
		if (senderId == null) {
			return null; // 시스템
		}
		return cache.computeIfAbsent(senderId,
				id -> staff.byId(id).map(CurrentStaff::name).orElse("#" + id));
	}

	private static @Nullable Ref ref(@Nullable String type, @Nullable Long id, @Nullable String label) {
		return type == null || id == null ? null : new Ref(RefType.valueOf(type), id, label == null ? "" : label);
	}

	/** 앞뒤 공백을 지우고 길이·식별번호를 검사 (테스트에서 직접 부른다) */
	static String text(@Nullable String value, int max, String field) {
		String v = value == null ? "" : value.strip();
		if (v.isEmpty() || v.length() > max) {
			throw new BusinessException("NOTE_TEXT", field + "은 1~" + max + "자로 입력하세요");
		}
		if (RESIDENT_NO.matcher(v).find()) {
			throw new BusinessException("NOTE_SENSITIVE",
					field + "에 주민등록번호 형식의 숫자가 있습니다. 식별번호는 쪽지로 보내지 말고 계약을 붙여 보내세요");
		}
		return v;
	}

	static void validateRef(@Nullable Ref ref) {
		if (ref != null && (ref.label() == null || ref.label().length() > 30)) {
			throw new BusinessException("NOTE_REF", "연결한 계약·신청 번호를 확인하세요");
		}
	}

	private static String truncate(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max - 1) + "…";
	}
}
