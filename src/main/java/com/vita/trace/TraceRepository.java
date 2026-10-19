package com.vita.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * traces, spans 테이블에 기록을 넣는다. 한 번 쓴 기록은 고치지 않으므로 넣는 일만 한다.
 *
 * <p>값은 모두 JDBC 파라미터로 바인딩하고 jsonb 칸은 {@code ?::jsonb}로 넣는다. 답변 저장 트랜잭션과 섞이지 않도록
 * 항상 새 트랜잭션({@code REQUIRES_NEW})에서 trace 하나와 그 span들을 함께 넣는다. 중간에 실패하면 일부만 남지 않고 전부 취소된다.
 */
@Repository
public class TraceRepository {

	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper;
	private final int maxJsonChars;

	public TraceRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
			@Value("${trace.max-history-chars:200000}") int maxJsonChars) {
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
		this.maxJsonChars = maxJsonChars;
	}

	/**
	 * trace와 span들을 한 트랜잭션으로 저장한다.
	 *
	 * @return 새로 만들어진 traces.id
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public long save(TraceRecord trace) {
		TraceSummary summary = TraceSummary.of(trace);
		KeyHolder keys = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			PreparedStatement statement = connection.prepareStatement(
					"INSERT INTO traces (message_id, app_version, config, created_at, question, unresolved_reason, total_ms, has_error)"
								+ " VALUES (?, ?, ?::jsonb, ?, ?, ?, ?, ?)",
					Statement.RETURN_GENERATED_KEYS);
			statement.setLong(1, trace.messageId());
			statement.setString(2, trace.appVersion());
			statement.setString(3, TraceJson.toJson(objectMapper, trace.config(), maxJsonChars));
			statement.setTimestamp(4, Timestamp.valueOf(trace.startedAt()));
			statement.setString(5, summary.question());
			statement.setString(6, summary.unresolvedReason());
			if (summary.totalMs() == null) {
				statement.setNull(7, java.sql.Types.INTEGER);
			} else {
				statement.setInt(7, summary.totalMs());
			}
			statement.setBoolean(8, summary.hasError());
			return statement;
		}, keys);
		Number key = keys.getKeys() == null ? null : (Number) keys.getKeys().get("id");
		if (key == null) {
			throw new IllegalStateException("traces.id를 받지 못했습니다. messageId=" + trace.messageId());
		}
		long traceId = key.longValue();

		jdbcTemplate.batchUpdate(
				"INSERT INTO spans (trace_id, type, started_at, ended_at, history) VALUES (?, ?, ?, ?, ?::jsonb)",
				trace.spans(), trace.spans().size(), (statement, span) -> {
					statement.setLong(1, traceId);
					statement.setString(2, span.type().code());
					statement.setTimestamp(3, Timestamp.valueOf(span.startedAt()));
					statement.setTimestamp(4, Timestamp.valueOf(span.endedAt()));
					statement.setString(5, TraceJson.toJson(objectMapper, span.history(), maxJsonChars));
				});
		return traceId;
	}

	/**
	 * 기준 시각보다 오래된 trace를 최대 {@code limit}건 지운다. spans는 FK의 {@code ON DELETE CASCADE}로 함께 지워진다.
	 * 한 번에 많이 지우면 테이블을 오래 잡고 있으므로, 부르는 쪽이 건수가 limit 미만일 때까지 나눠서 반복한다.
	 *
	 * @param cutoff 이 시각보다 먼저 만들어진 trace가 대상
	 * @param limit  한 번에 지울 최대 건수
	 * @return 지운 trace 건수
	 */
	@Transactional
	public int deleteCreatedBefore(LocalDateTime cutoff, int limit) {
		return jdbcTemplate.update(
				"DELETE FROM traces WHERE id IN (SELECT id FROM traces WHERE created_at < ? ORDER BY id LIMIT ?)",
				Timestamp.valueOf(cutoff), limit);
	}
}
