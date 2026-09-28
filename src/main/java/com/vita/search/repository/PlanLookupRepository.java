package com.vita.search.repository;

import com.vita.search.dto.PlanReference;
import com.vita.search.service.PlanSortKey;
import java.time.LocalDateTime;
import java.util.List;
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
}
