package com.vita.search.repository;

import com.pgvector.PGvector;
import com.vita.search.dto.FaqSimilarityResult;
import com.vita.search.entity.FaqStatus;
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
 * pgvector 유사도(cosine) 검색 — Phase 2 전용. Spring Data JPA의 @Query(JPQL)로는 pgvector
 * 전용 연산자(<=>)를 쓸 수 없어서, JdbcTemplate으로 순수 SQL을 직접 실행한다.
 *
 * {@link com.vita.search.service.FaqRetrievalServiceImpl}에서 사용자 질문을
 * {@link com.vita.embedding.EmbeddingProvider}로 벡터화한 뒤 이 클래스를 호출한다.
 */
@Repository
public class FaqVectorSearchRepository {

	/**
	 * `<=>`는 cosine distance(0=완전 동일, 2=반대)라서 작을수록 유사하다. 유사도로 쓰려면
	 * 1 - distance로 뒤집어야 하고, 정렬은 distance 기준 오름차순(ASC)이어야 가장 유사한
	 * 것이 먼저 나온다. WHERE 절의 유사도 조건도 같은 식으로 뒤집어서 threshold를 건다.
	 */
	private static final String SEARCH_SQL = """
			SELECT id, category, subcategory, question, answer, updated_at,
			       1 - (embedding <=> ?) AS similarity
			FROM faqs
			WHERE status = ?
			  AND embedding IS NOT NULL
			  AND 1 - (embedding <=> ?) >= ?
			ORDER BY embedding <=> ?
			LIMIT ?
			""";

	private final JdbcTemplate jdbcTemplate;

	public FaqVectorSearchRepository(DataSource dataSource) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
	}

	/**
	 * 코사인 유사도 기준으로 가장 유사한 FAQ를 topK개 반환한다.
	 *
	 * @param queryVector        검색할 쿼리 벡터 (embedding 컬럼과 같은 차원이어야 함)
	 * @param status             이 상태인 FAQ만 대상 (보통 ACTIVE)
	 * @param similarityThreshold 이 값 미만인 결과는 제외 (0~1, 1에 가까울수록 유사)
	 * @param topK               최대 반환 개수
	 * @return 유사도 내림차순으로 정렬된 결과 목록
	 */
	public List<FaqSimilarityResult> searchBySimilarity(
			float[] queryVector, FaqStatus status, double similarityThreshold, int topK) {

		ConnectionCallback<List<FaqSimilarityResult>> action = connection -> {
			// PGvector를 PreparedStatement 파라미터로 바인딩하려면, 커넥션 풀에서 꺼낸
			// 커넥션마다 pgvector 타입을 등록해줘야 드라이버가 vector 컬럼을 이해한다.
			PGvector.registerTypes(connection);
			PGvector vector = new PGvector(queryVector);

			try (PreparedStatement statement = connection.prepareStatement(SEARCH_SQL)) {
				statement.setObject(1, vector);
				statement.setString(2, status.name());
				statement.setObject(3, vector);
				statement.setDouble(4, similarityThreshold);
				statement.setObject(5, vector);
				statement.setInt(6, topK);

				List<FaqSimilarityResult> results = new ArrayList<>();
				try (ResultSet resultSet = statement.executeQuery()) {
					while (resultSet.next()) {
						results.add(new FaqSimilarityResult(
								resultSet.getLong("id"),
								resultSet.getString("category"),
								resultSet.getString("subcategory"),
								resultSet.getString("question"),
								resultSet.getString("answer"),
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
