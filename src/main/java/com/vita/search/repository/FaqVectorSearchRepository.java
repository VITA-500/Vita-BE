package com.vita.search.repository;

import com.pgvector.PGvector;
import com.vita.search.dto.FaqSimilarityResult;
import com.vita.search.entity.FaqStatus;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
 * BE2의 사용자 질문 → 벡터 변환 인터페이스가 아직 없어서, 이 클래스는 "벡터가 주어졌을 때
 * 유사도 검색 자체가 올바르게 동작하는가"만 검증하기 위한 것이다. Service/Controller는
 * BE2 인터페이스가 나온 뒤에 붙인다.
 */
@Repository
public class FaqVectorSearchRepository {

	/**
	 * `<=>`는 cosine distance(0=완전 동일, 2=반대)라서 작을수록 유사하다. 유사도로 쓰려면
	 * 1 - distance로 뒤집어야 하고, 정렬은 distance 기준 오름차순(ASC)이어야 가장 유사한
	 * 것이 먼저 나온다. WHERE 절의 유사도 조건도 같은 식으로 뒤집어서 threshold를 건다.
	 */
	private static final String SEARCH_SQL = """
			SELECT id, category, subcategory, question, answer,
			       1 - (embedding <=> ?) AS similarity
			FROM faq
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
								resultSet.getDouble("similarity")));
					}
				}
				return results;
			}
		};

		return jdbcTemplate.execute(action);
	}
}
