package com.vita.search.repository;

import com.vita.search.dto.PlanReference;
import com.vita.search.service.PlanQueryConditions;
import com.vita.search.service.PlanSortKey;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 요금제 비교·최상급 질문(가장 저렴한/비싼, 데이터 제일 많은/적은)에 답하기 위한 정형 조건 조회.
 * 벡터 유사도로는 monthly_fee/base_data_mb 같은 실제 수치 비교가 안 되는 것을 실측으로
 * 확인해서, {@link PlanVectorSearchRepository}(유사도 검색)와는 별도 경로로 뒀다.
 */
@Repository
public class PlanLookupRepository {

	// 데이터량 비교는 무제한 요금제(base_data_mb IS NULL)를 특별 취급해야 한다 — 단순히
	// 숫자로만 정렬하면 무제한 요금제가 "데이터가 없는 것"처럼 취급돼 최하위로 밀린다.
	// (data_policy = 'UNLIMITED')를 1차 정렬 기준으로 앞세워 이 문제를 해결한다.
	private static final String MOST_DATA_ORDER = "(data_policy = 'UNLIMITED') DESC, base_data_mb DESC, id ASC";
	private static final String LEAST_DATA_ORDER = "(data_policy = 'UNLIMITED') ASC, base_data_mb ASC, id ASC";

	private final JdbcTemplate jdbcTemplate;

	public PlanLookupRepository(DataSource dataSource) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
	}

	/**
	 * sortKey 기준으로 정렬된 ACTIVE 요금제를 limit개 조회한다. 유사도 검색이 아니라 정확한
	 * 조건 매칭이라 {@link PlanReference#similarity()}는 항상 1.0으로 채운다.
	 *
	 * <p>ORDER BY 절은 사용자 입력이 아니라 {@link PlanSortKey}(닫힌 enum)로만 정해지는
	 * 고정 문자열 중 하나라 SQL 인젝션 위험이 없다 — JDBC 파라미터 바인딩은 값만 가능하고
	 * 정렬 기준(컬럼/표현식) 자체는 바인딩할 수 없어 이 방식을 썼다.
	 */
	public List<PlanReference> findByExtreme(PlanSortKey sortKey, int limit) {
		String orderBy = switch (sortKey) {
			case CHEAPEST -> "monthly_fee ASC, id ASC";
			case MOST_EXPENSIVE -> "monthly_fee DESC, id ASC";
			case MOST_DATA -> MOST_DATA_ORDER;
			case LEAST_DATA -> LEAST_DATA_ORDER;
		};

		String sql = """
				SELECT id, plan_code, name, summary, monthly_fee, description, updated_at
				FROM plans
				WHERE status = 'ACTIVE'
				ORDER BY %s
				LIMIT ?
				""".formatted(orderBy);

		return jdbcTemplate.query(sql,
				(rs, rowNum) -> new PlanReference(
						rs.getLong("id"),
						rs.getString("plan_code"),
						rs.getString("name"),
						rs.getString("summary"),
						rs.getInt("monthly_fee"),
						rs.getString("description"),
						1.0,
						rs.getObject("updated_at", LocalDateTime.class)),
				limit);
	}

	/**
	 * 질문에서 추출한 조건({@link PlanQueryConditions})을 모두 만족하는 ACTIVE 요금제의 plan_code 집합을 조회한다.
	 *
	 * <p>WHERE 절은 조건이 있는 항목마다 고정된 SQL 조각만 이어 붙이고, 값은 전부 JDBC 파라미터로 바인딩하므로
	 * 사용자 입력이 SQL 문자열에 들어가지 않는다. 데이터 하한만 있는 경우("80기가 이상")는 무제한 요금제도
	 * 조건을 만족하는 것으로 본다(base_data_mb가 NULL이라 숫자 비교만으로는 빠지기 때문).
	 */
	public Set<String> findPlanCodesByConditions(PlanQueryConditions conditions) {
		StringBuilder sql = new StringBuilder("SELECT plan_code FROM plans WHERE status = 'ACTIVE'");
		List<Object> params = new ArrayList<>();

		if (conditions.feeMin() != null) {
			sql.append(" AND monthly_fee >= ?");
			params.add(conditions.feeMin());
		}
		if (conditions.feeMax() != null) {
			sql.append(" AND monthly_fee <= ?");
			params.add(conditions.feeMax());
		}
		if (conditions.dataMbMin() != null) {
			if (conditions.dataMbMax() == null) {
				sql.append(" AND (base_data_mb >= ? OR data_policy = 'UNLIMITED')");
			} else {
				sql.append(" AND base_data_mb >= ?");
			}
			params.add(conditions.dataMbMin());
		}
		if (conditions.dataMbMax() != null) {
			sql.append(" AND base_data_mb <= ?");
			params.add(conditions.dataMbMax());
		}
		if (conditions.targetGroup() != null) {
			sql.append(" AND target_group = ?");
			params.add(conditions.targetGroup());
		}
		if (conditions.dataPolicy() != null) {
			sql.append(" AND data_policy = ?");
			params.add(conditions.dataPolicy());
		}

		return new HashSet<>(jdbcTemplate.queryForList(sql.toString(), String.class, params.toArray()));
	}
}
