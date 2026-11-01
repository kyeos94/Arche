package dev.arche.collection.internal;

import dev.arche.collection.AlertAssignments;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DefaultAlertAssignments implements AlertAssignments {

	private final JdbcClient jdbc;

	DefaultAlertAssignments(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public int openCount(long staffId) {
		return jdbc.sql("select count(*) from collection_alert where staff_id = :s and read_at is null")
				.param("s", staffId).query(Integer.class).single();
	}

	@Override
	@Transactional
	public int reassignOpen(long fromStaffId, long toStaffId) {
		return jdbc.sql("update collection_alert set staff_id = :to where staff_id = :from and read_at is null")
				.param("from", fromStaffId).param("to", toStaffId).update();
	}
}
