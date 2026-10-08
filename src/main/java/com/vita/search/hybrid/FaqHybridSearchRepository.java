package com.vita.search.hybrid;

import com.pgvector.PGvector;
import com.vita.search.dto.FaqSimilarityResult;
import com.vita.search.entity.FaqStatus;
import com.vita.search.repository.FaqVectorSearchRepository;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * FAQ Hybrid 검색: 벡터 검색과 키워드(BM25) 검색을 한 SQL에서 가중 RRF로 합친다. LLM·임베딩 호출 없이 DB 안에서 끝난다.
 *
 * <p>순서는 RRF 점수 내림차순이고(동점이면 벡터 순위가 앞선 쪽), {@code similarity}는 RRF 점수가 아니라 질문 벡터와의
 * <b>코사인 유사도</b>다. 키워드 검색으로만 올라온 후보도 같은 식으로 계산해서 채우므로, threshold 판정과 분류 가산점 같은 후처리가
 * 코드 변경 없이 그대로 동작한다. 컬럼과 반환 형식은 {@link FaqVectorSearchRepository}와 같다.
 *
 * <p>pg_search 확장과 {@code faqs} BM25 인덱스가 필요하다(로컬 ParadeDB 전용, {@link Bm25IndexInitializer} 참고).
 */
@Repository
@DependsOn("bm25IndexInitializer")
@ConditionalOnProperty(prefix = "search.hybrid", name = "enabled", havingValue = "true")
public class FaqHybridSearchRepository {

	private static final String SEARCH_SQL = """
			WITH qv AS (SELECT ?::vector AS v),
			vec AS (
			  SELECT id, row_number() OVER (ORDER BY d, id) AS r FROM (
			    SELECT f.id AS id, f.embedding <=> qv.v AS d FROM faqs f, qv
			    WHERE f.status = ? AND f.embedding IS NOT NULL
			    ORDER BY d LIMIT ?) s),
			kw AS (
			  SELECT id, row_number() OVER (ORDER BY sc DESC, id) AS r FROM (
			    SELECT id, pdb.score(id) AS sc FROM faqs
			    WHERE status = ? AND (question ||| ?::text OR answer ||| ?::text)
			    ORDER BY sc DESC LIMIT ?) s),
			fused AS (
			  SELECT COALESCE(vec.id, kw.id) AS id, vec.r AS vr,
			         COALESCE(?::float8 / (?::int + vec.r), 0) + COALESCE(?::float8 / (?::int + kw.r), 0) AS rrf
			  FROM vec FULL OUTER JOIN kw ON vec.id = kw.id)
			SELECT f.id, f.category, f.subcategory, f.question, f.answer, f.updated_at,
			       1 - (f.embedding <=> qv.v) AS similarity
			FROM fused JOIN faqs f ON f.id = fused.id CROSS JOIN qv
			WHERE f.embedding IS NOT NULL
			ORDER BY fused.rrf DESC, fused.vr ASC NULLS LAST, f.id
			LIMIT ?
			""";

	private final JdbcTemplate jdbcTemplate;

	public FaqHybridSearchRepository(DataSource dataSource) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
	}

	/**
	 * @param queryVector    질문 벡터(embedding 컬럼과 같은 차원)
	 * @param keywordText    키워드 검색에 쓸 텍스트({@link Bm25QueryText}로 다듬은 것, 비어 있으면 안 된다)
	 * @param weights        벡터·키워드 가중치와 RRF의 k
	 * @param candidateLimit 벡터·키워드 검색에서 각각 합칠 후보 수의 상한
	 * @param poolSize       돌려줄 최대 개수
	 * @return RRF 점수 내림차순 후보. similarity는 코사인 유사도
	 */
	public List<FaqSimilarityResult> search(
			float[] queryVector, String keywordText, HybridWeights weights, int candidateLimit, int poolSize) {

		ConnectionCallback<List<FaqSimilarityResult>> action = connection -> {
			PGvector.registerTypes(connection);
			PGvector vector = new PGvector(queryVector);
			String status = FaqStatus.ACTIVE.name();

			try (PreparedStatement statement = connection.prepareStatement(SEARCH_SQL)) {
				int i = 1;
				statement.setObject(i++, vector);
				statement.setString(i++, status);
				statement.setInt(i++, candidateLimit);
				statement.setString(i++, status);
				statement.setString(i++, keywordText);
				statement.setString(i++, keywordText);
				statement.setInt(i++, candidateLimit);
				statement.setDouble(i++, weights.vector());
				statement.setInt(i++, weights.rrfK());
				statement.setDouble(i++, weights.keyword());
				statement.setInt(i++, weights.rrfK());
				statement.setInt(i, poolSize);

				List<FaqSimilarityResult> results = new ArrayList<>();
				try (ResultSet resultSet = statement.executeQuery()) {
					while (resultSet.next()) {
						results.add(FaqVectorSearchRepository.mapRow(resultSet));
					}
				}
				return results;
			}
		};

		return jdbcTemplate.execute(action);
	}
}
