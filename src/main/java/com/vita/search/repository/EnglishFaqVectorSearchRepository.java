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
 * 영어 FAQ 임베딩(faqs_en) 유사도 검색 — 실험 B(영어 Retrieval) 전용. {@link FaqVectorSearchRepository}와 같은 이유
 * (pgvector 전용 연산자 <=>)로 JdbcTemplate 순수 SQL을 직접 실행한다.
 *
 * <p>유사도와 순위는 영어 임베딩(faqs_en.embedding)으로 계산하고, 돌려주는 값은 원본 FAQ(faqs)의 id와 한국어
 * category/subcategory/question/answer다. 같은 정답지로 채점되고, 답변 중복 제거·분류 가산점·Context 구성이 한국어
 * 원문 기준으로 그대로 동작하게 하기 위해서다. 상태(ACTIVE)도 원본 FAQ의 값으로 거른다.
 */
@Repository
public class EnglishFaqVectorSearchRepository {

	private static final String SEARCH_SQL = """
			SELECT f.id, f.category, f.subcategory, f.question, f.answer, f.updated_at,
			       1 - (e.embedding <=> ?) AS similarity
			FROM faqs_en e
			JOIN faqs f ON f.id = e.faq_id
			WHERE f.status = ?
			  AND e.embedding IS NOT NULL
			  AND 1 - (e.embedding <=> ?) >= ?
			ORDER BY e.embedding <=> ?
			LIMIT ?
			""";

	private final JdbcTemplate jdbcTemplate;

	public EnglishFaqVectorSearchRepository(DataSource dataSource) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
	}

	/**
	 * 영어 임베딩과의 코사인 유사도 기준으로 가장 유사한 FAQ를 topK개 반환한다.
	 *
	 * @param queryVector        검색할 쿼리 벡터 (영어로 번역한 질문의 임베딩)
	 * @param status             원본 FAQ가 이 상태인 것만 대상 (보통 ACTIVE)
	 * @param similarityThreshold 이 값 미만인 결과는 제외 (0~1, 1에 가까울수록 유사)
	 * @param topK               최대 반환 개수
	 * @return 유사도 내림차순으로 정렬된 결과 목록 (id와 본문은 원본 한국어 FAQ)
	 */
	public List<FaqSimilarityResult> searchBySimilarity(
			float[] queryVector, FaqStatus status, double similarityThreshold, int topK) {

		ConnectionCallback<List<FaqSimilarityResult>> action = connection -> {
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
