package com.vita.trace;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * traces, spans 조회. 관리자 조회 API 전용이라 읽기만 한다.
 *
 * <p>값은 모두 JDBC 파라미터로 바인딩하고, 정렬 기준은 화이트리스트에서 고른 고정 문자열만 SQL에 붙인다. jsonb 키 존재 검사는
 * {@code ?} 연산자가 JDBC 파라미터 표시와 겹쳐서 {@code jsonb_exists()} 함수로 쓴다.
 */
@Repository
public class TraceQueryRepository {

	/** 목록 조건. null인 항목은 걸지 않는다. */
	public record Filter(Long messageId, LocalDateTime from, LocalDateTime to, String unresolvedReason, String keyword) {
	}

	/** traces 한 줄(설정 스냅샷은 JSON 문자열 그대로). */
	public record TraceRow(Long id, Long messageId, String appVersion, LocalDateTime createdAt, String configJson) {
	}

	/** spans 한 줄(history는 JSON 문자열 그대로). */
	public record SpanRow(Long id, String type, LocalDateTime startedAt, LocalDateTime endedAt, String historyJson) {
	}

	/** 목록 정렬 기준(API 필드 이름 → SQL 열). 이 밖의 값은 서비스에서 거부한다. */
	private static final java.util.Map<String, String> SORT_COLUMNS = java.util.Map.of(
			"id", "x.id", "createdAt", "x.created_at", "totalMs", "x.total_ms");

	/** 목록 한 줄. 질문·미해결 사유·총 시간·오류 여부는 저장할 때 {@code traces}에 요약해 둔 컬럼이라 단계 history를 읽지 않는다. */
	private static final String ITEM_SELECT =
			"SELECT x.id, x.message_id, x.app_version, x.created_at, x.question, x.unresolved_reason, x.total_ms, x.has_error";

	private final JdbcTemplate jdbcTemplate;

	public TraceQueryRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/** 조건에 맞는 trace를 한 페이지 돌려준다. 정렬 기준이 같으면 id가 큰(최근) 것이 먼저다. */
	public Page<TraceViews.Item> findPage(Filter filter, Pageable pageable) {
		List<Object> params = new ArrayList<>();
		String where = where(filter, params);
		String base = "FROM traces x" + where;

		Long total = jdbcTemplate.queryForObject("SELECT count(*) " + base, Long.class, params.toArray());
		List<Object> pageParams = new ArrayList<>(params);
		pageParams.add(pageable.getPageSize());
		pageParams.add(pageable.getOffset());
		List<TraceViews.Item> items = jdbcTemplate.query(
				ITEM_SELECT + " " + base + orderBy(pageable.getSort()) + " LIMIT ? OFFSET ?",
				(rs, rowNum) -> toItem(rs), pageParams.toArray());
		return new PageImpl<>(items, pageable, total == null ? 0 : total);
	}

	public Optional<TraceRow> findTrace(long id) {
		return jdbcTemplate.query(
				"SELECT id, message_id, app_version, created_at, config::text AS config FROM traces WHERE id = ?",
				(rs, rowNum) -> new TraceRow(rs.getLong("id"), rs.getLong("message_id"), rs.getString("app_version"),
						time(rs, "created_at"), rs.getString("config")),
				id).stream().findFirst();
	}

	/** trace의 단계들을 일어난 순서(id 순)로 돌려준다. */
	public List<SpanRow> findSpans(long traceId) {
		return jdbcTemplate.query(
				"SELECT id, type, started_at, ended_at, history::text AS history FROM spans WHERE trace_id = ? ORDER BY id",
				(rs, rowNum) -> new SpanRow(rs.getLong("id"), rs.getString("type"), time(rs, "started_at"),
						time(rs, "ended_at"), rs.getString("history")),
				traceId);
	}

	private static String where(Filter f, List<Object> params) {
		List<String> conditions = new ArrayList<>();
		if (f.messageId() != null) {
			conditions.add("x.message_id = ?");
			params.add(f.messageId());
		}
		if (f.from() != null) {
			conditions.add("x.created_at >= ?");
			params.add(Timestamp.valueOf(f.from()));
		}
		if (f.to() != null) {
			conditions.add("x.created_at < ?");
			params.add(Timestamp.valueOf(f.to()));
		}
		if (f.unresolvedReason() != null && !f.unresolvedReason().isBlank()) {
			conditions.add("x.unresolved_reason = ?");
			params.add(f.unresolvedReason());
		}
		if (f.keyword() != null && !f.keyword().isBlank()) {
			conditions.add("x.question ILIKE ? ESCAPE '!'");
			params.add("%" + escapeLike(f.keyword().trim()) + "%");
		}
		return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
	}

	/** LIKE의 특수문자(%, _)와 이스케이프 문자(!)를 글자 그대로 찾도록 바꾼다. */
	static String escapeLike(String keyword) {
		return keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_");
	}

	private static String orderBy(Sort sort) {
		List<String> parts = new ArrayList<>();
		for (Sort.Order order : sort) {
			String column = SORT_COLUMNS.get(order.getProperty());
			if (column != null) {
				parts.add(column + (order.isAscending() ? " ASC" : " DESC") + (column.equals("x.total_ms") ? " NULLS LAST" : ""));
			}
		}
		parts.add("x.id DESC");
		return " ORDER BY " + String.join(", ", parts);
	}

	private static TraceViews.Item toItem(ResultSet rs) throws SQLException {
		long totalMs = rs.getLong("total_ms");
		Long total = rs.wasNull() ? null : totalMs;
		return new TraceViews.Item(rs.getLong("id"), rs.getLong("message_id"), time(rs, "created_at"),
				rs.getString("app_version"), rs.getString("question"), rs.getString("unresolved_reason"),
				total, rs.getBoolean("has_error"));
	}

	private static LocalDateTime time(ResultSet rs, String column) throws SQLException {
		Timestamp timestamp = rs.getTimestamp(column);
		return timestamp == null ? null : timestamp.toLocalDateTime();
	}
}
