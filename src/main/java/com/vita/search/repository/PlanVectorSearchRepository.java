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
 * pgvector 유사도(cosine) 검색 — 요금제(plans) 전용. {@link FaqVectorSearchRepository}와
 * 같은 이유(pgvector 전용 연산자 <=>)로 JdbcTemplate 순수 SQL을 직접 실행한다.
 *
 * <p>요금제는 상태 값을 별도 enum으로 관리하지 않는다 — plans 테이블의 status는 항상
 * ACTIVE만 검색 대상이면 되고(FAQ처럼 다른 상태로 조회할 일이 없음), enum을 새로 만드는
 * 대신 SQL에 'ACTIVE'를 그대로 고정했다.
 */
@Repository
public class PlanVectorSearchRepository {

	private static final String SEARCH_SQL = """
			SELECT id, plan_code, name, summary, monthly_fee, description, updated_at,
			       1 - (embedding <=> ?) AS similarity
			FROM plans
			WHERE status = 'ACTIVE'
			  AND embedding IS NOT NULL
			  AND 1 - (embedding <=> ?) >= ?
			ORDER BY embedding <=> ?
			LIMIT ?
			""";

	private final JdbcTemplate jdbcTemplate;

	public PlanVectorSearchRepository(DataSource dataSource) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
	}

	/**
	 * 코사인 유사도 기준으로 가장 유사한 요금제를 topK개 반환한다.
	 *
	 * @param queryVector        검색할 쿼리 벡터 (FAQ와 동일한 임베딩 모델·768차원)
	 * @param similarityThreshold 이 값 미만인 결과는 제외 (0~1, 1에 가까울수록 유사)
	 * @param topK               최대 반환 개수
	 * @return 유사도 내림차순으로 정렬된 결과 목록
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
								resultSet.getObject("updated_at", LocalDateTime.class)));
					}
				}
				return results;
			}
		};

		return jdbcTemplate.execute(action);
	}
}
