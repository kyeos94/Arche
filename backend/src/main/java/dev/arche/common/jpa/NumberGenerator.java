package dev.arche.common.jpa;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 외부에 노출하는 번호(계약번호 등)를 DB 시퀀스로 만든다. 예: prefix "L" + 시퀀스 → L00001001
 */
@Component
public class NumberGenerator {

	private final JdbcClient jdbc;

	NumberGenerator(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public String next(String sequence, String prefix) {
		if (!sequence.matches("[a-z_]+")) {
			throw new IllegalArgumentException(sequence);
		}
		long n = jdbc.sql("select nextval('" + sequence + "')").query(Long.class).single();
		return prefix + String.format("%08d", n);
	}
}
