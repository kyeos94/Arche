package dev.arche.integration.internal.fixedlength;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link FixedLengthRecord} 레코드 ↔ 바이트 변환기 (guide 11.2 "자체 FixedLengthRecord 매퍼").
 * <ul>
 * <li>길이는 바이트 기준. 문자열이 길면 문자 경계에서 자른다 (한글 한 글자를 반으로 쪼개지 않음)</li>
 * <li>숫자가 자리수를 넘거나 음수면 오류 (금액을 잘라서 보내면 안 되므로)</li>
 * <li>필드 위치가 겹치거나 레코드 길이를 넘으면 레이아웃 오류</li>
 * </ul>
 */
public final class FixedLengthMapper {

	private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

	private record Slot(int index, RecordComponent component, java.lang.reflect.Method accessor, int offset, int length) {
	}

	private record Layout(int length, List<Slot> slots, Constructor<?> constructor) {
	}

	private final Charset charset;
	private final Map<Class<?>, Layout> layouts = new ConcurrentHashMap<>();

	public FixedLengthMapper(Charset charset) {
		this.charset = charset;
	}

	public int lengthOf(Class<?> type) {
		return layout(type).length();
	}

	public byte[] encode(Object record) {
		Layout layout = layout(record.getClass());
		byte[] out = new byte[layout.length()];
		Arrays.fill(out, (byte) ' ');
		for (Slot s : layout.slots()) {
			Object value;
			try {
				value = s.accessor().invoke(record);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
			byte[] bytes = format(s, value);
			System.arraycopy(bytes, 0, out, s.offset(), bytes.length);
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	public <T> T decode(byte[] line, Class<T> type) {
		Layout layout = layout(type);
		if (line.length < layout.length()) {
			throw new IllegalArgumentException("레코드 길이 부족: " + line.length + " < " + layout.length());
		}
		Object[] args = new Object[layout.slots().size()];
		for (Slot s : layout.slots()) {
			args[s.index()] = parse(s.component().getType(), new String(line, s.offset(), s.length(), charset));
		}
		try {
			return (T) layout.constructor().newInstance(args);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private byte[] format(Slot s, Object value) {
		Class<?> t = s.component().getType();
		if (t == String.class) {
			byte[] text = truncate(value == null ? "" : (String) value, s.length());
			byte[] out = new byte[s.length()];
			Arrays.fill(out, (byte) ' ');
			System.arraycopy(text, 0, out, 0, text.length);
			return out;
		}
		String digits;
		if (t == int.class || t == long.class || t == Integer.class || t == Long.class) {
			long n = value == null ? 0 : ((Number) value).longValue();
			if (n < 0) {
				throw new IllegalArgumentException(s.component().getName() + ": 음수는 쓸 수 없습니다 (" + n + ")");
			}
			digits = Long.toString(n);
			if (digits.length() > s.length()) {
				throw new IllegalArgumentException(s.component().getName() + ": " + s.length() + "자리를 넘습니다");
			}
			digits = "0".repeat(s.length() - digits.length()) + digits;
		} else if (t == LocalDate.class) {
			if (s.length() != 8) {
				throw new IllegalArgumentException(s.component().getName() + ": 날짜는 8바이트(yyyyMMdd)입니다");
			}
			digits = value == null ? " ".repeat(8) : DATE.format((LocalDate) value);
		} else {
			throw new IllegalArgumentException("지원하지 않는 타입: " + t);
		}
		return digits.getBytes(charset);
	}

	private static Object parse(Class<?> t, String raw) {
		if (t == String.class) {
			return raw.stripTrailing();
		}
		String v = raw.trim();
		if (t == int.class || t == Integer.class) {
			return v.isEmpty() ? 0 : Integer.parseInt(v);
		}
		if (t == long.class || t == Long.class) {
			return v.isEmpty() ? 0L : Long.parseLong(v);
		}
		if (t == LocalDate.class) {
			return v.isEmpty() ? null : LocalDate.parse(v, DATE);
		}
		throw new IllegalArgumentException("지원하지 않는 타입: " + t);
	}

	/** maxBytes 안에 들어가는 만큼만 인코딩. 버퍼가 차면 그 문자 앞에서 멈추므로 문자가 반으로 잘리지 않는다 */
	byte[] truncate(String text, int maxBytes) {
		CharsetEncoder encoder = charset.newEncoder().onMalformedInput(CodingErrorAction.REPLACE)
				.onUnmappableCharacter(CodingErrorAction.REPLACE);
		ByteBuffer out = ByteBuffer.allocate(maxBytes);
		encoder.encode(CharBuffer.wrap(text), out, true);
		return Arrays.copyOf(out.array(), out.position());
	}

	private Layout layout(Class<?> type) {
		return layouts.computeIfAbsent(type, FixedLengthMapper::build);
	}

	private static Layout build(Class<?> type) {
		FixedLengthRecord rec = type.getAnnotation(FixedLengthRecord.class);
		if (rec == null || !type.isRecord()) {
			throw new IllegalArgumentException(type + "는 @FixedLengthRecord record가 아닙니다");
		}
		RecordComponent[] components = type.getRecordComponents();
		Slot[] all = new Slot[components.length];
		for (int i = 0; i < components.length; i++) {
			FixedLengthRecord.Field f = components[i].getAnnotation(FixedLengthRecord.Field.class);
			if (f == null) {
				throw new IllegalArgumentException(
						type.getSimpleName() + "." + components[i].getName() + "에 @Field가 없습니다");
			}
			java.lang.reflect.Method accessor = components[i].getAccessor();
			accessor.setAccessible(true);
			all[i] = new Slot(i, components[i], accessor, f.offset(), f.length());
		}
		List<Slot> slots = Arrays.stream(all).sorted(Comparator.comparingInt(Slot::offset)).toList();
		int end = 0;
		for (Slot s : slots) {
			if (s.offset() < end) {
				throw new IllegalArgumentException(
						type.getSimpleName() + "." + s.component().getName() + ": 앞 필드와 겹칩니다");
			}
			end = s.offset() + s.length();
		}
		if (end > rec.length()) {
			throw new IllegalArgumentException(type.getSimpleName() + ": 필드가 레코드 길이 " + rec.length() + "를 넘습니다");
		}
		try {
			Class<?>[] params = Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);
			Constructor<?> ctor = type.getDeclaredConstructor(params);
			ctor.setAccessible(true);
			return new Layout(rec.length(), slots, ctor);
		} catch (NoSuchMethodException e) {
			throw new IllegalStateException(e);
		}
	}
}
