package com.vita.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
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
		KeyHolder keys = new GeneratedKeyHolder();
		jdbcTemplate.update(connection -> {
			PreparedStatement statement = connection.prepareStatement(
					"INSERT INTO traces (message_id, app_version, config, created_at) VALUES (?, ?, ?::jsonb, ?)",
					Statement.RETURN_GENERATED_KEYS);
			statement.setLong(1, trace.messageId());
			statement.setString(2, trace.appVersion());
			statement.setString(3, TraceJson.toJson(objectMapper, trace.config(), maxJsonChars));
			statement.setTimestamp(4, Timestamp.valueOf(trace.startedAt()));
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
}
