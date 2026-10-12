package com.vita.search.repository;

import com.pgvector.PGvector;
import com.vita.search.dto.PlanSimilarityResult;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 영어 요금제 임베딩(plans_en) 유사도 검색 — 실험 B(영어 Retrieval) 전용. {@link PlanVectorSearchRepository}와 같은
 * 방식이고, 유사도와 순위만 영어 임베딩(plans_en.embedding)으로 계산한다.
 *
 * <p>돌려주는 값은 원본 요금제(plans)의 id·plan_code·한국어 name/summary/description과 속성(월 요금, 대상, 데이터·통화·문자
 * 정책)이다. 요금제 조건 매칭과 정답지, BE4의 답변 생성이 원본 값을 쓰기 때문이다.
 */
@Repository
public class EnglishPlanVectorSearchRepository {

	private static final String SEARCH_SQL = """
			SELECT p.id, p.plan_code, p.name, p.summary, p.monthly_fee, p.description, p.updated_at,
			       p.network_type, p.target_group, p.min_age, p.max_age,
			       p.data_policy, p.base_data_mb, p.exhausted_speed_kbps,
			       p.voice_policy, p.voice_minutes, p.sms_policy, p.sms_count,
			       1 - (e.embedding <=> ?) AS similarity
			FROM plans_en e
			JOIN plans p ON p.id = e.plan_id
			WHERE p.status = 'ACTIVE'
			  AND e.embedding IS NOT NULL
			  AND 1 - (e.embedding <=> ?) >= ?
			ORDER BY e.embedding <=> ?
			LIMIT ?
			""";

	private final JdbcTemplate jdbcTemplate;

	public EnglishPlanVectorSearchRepository(DataSource dataSource) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
	}

	/**
	 * 영어 임베딩과의 코사인 유사도 기준으로 가장 유사한 요금제를 topK개 반환한다.
	 *
	 * @param queryVector        검색할 쿼리 벡터 (영어로 번역한 질문의 임베딩)
	 * @param similarityThreshold 이 값 미만인 결과는 제외 (0~1, 1에 가까울수록 유사)
	 * @param topK               최대 반환 개수
	 * @return 유사도 내림차순으로 정렬된 결과 목록 (id와 속성은 원본 요금제)
	 */
	public List<PlanSimilarityResult> searchBySimilarity(float[] queryVector, double similarityThreshold, int topK) {
		ConnectionCallback<List<PlanSimilarityResult>> action = connection -> {
			PGvector.registerTypes(connection);
			PGvector vector = new PGvector(queryVector);

			try (PreparedStatement statement = connection.prepareStatement(SEARCH_SQL)) {
				statement.setObject(1, vector);
				statement.setObject(2, vector);
				statement.setDouble(3, similarityThreshold);
				statement.setObject(4, vector);
				statement.setInt(5, topK);

				List<PlanSimilarityResult> results = new ArrayList<>();
				try (ResultSet resultSet = statement.executeQuery()) {
					while (resultSet.next()) {
						results.add(new PlanSimilarityResult(
								resultSet.getLong("id"),
								resultSet.getString("plan_code"),
								resultSet.getString("name"),
								resultSet.getString("summary"),
								resultSet.getInt("monthly_fee"),
								resultSet.getString("description"),
								resultSet.getDouble("similarity"),
								resultSet.getObject("updated_at", LocalDateTime.class),
								resultSet.getString("network_type"),
								resultSet.getString("target_group"),
								resultSet.getObject("min_age", Integer.class),
								resultSet.getObject("max_age", Integer.class),
								resultSet.getString("data_policy"),
								resultSet.getObject("base_data_mb", Long.class),
								resultSet.getObject("exhausted_speed_kbps", Integer.class),
								resultSet.getString("voice_policy"),
								resultSet.getObject("voice_minutes", Integer.class),
								resultSet.getString("sms_policy"),
								resultSet.getObject("sms_count", Integer.class)));
					}
				}
				return results;
			}
		};

		return jdbcTemplate.execute(action);
	}
}
