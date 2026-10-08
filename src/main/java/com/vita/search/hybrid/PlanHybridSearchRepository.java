package com.vita.search.hybrid;

import com.pgvector.PGvector;
import com.vita.search.dto.PlanSimilarityResult;
import com.vita.search.repository.PlanVectorSearchRepository;
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
 * 요금제 Hybrid 검색: 벡터 검색과 키워드(BM25) 검색을 한 SQL에서 가중 RRF로 합친다. {@link FaqHybridSearchRepository}의
 * 요금제 버전이고, 키워드는 요금제 이름·요약·설명에서 찾는다. {@code similarity}는 RRF 점수가 아니라 코사인 유사도이며,
 * 컬럼과 반환 형식은 {@link PlanVectorSearchRepository}와 같다.
 *
 * <p>pg_search 확장과 {@code plans} BM25 인덱스가 필요하다(로컬 ParadeDB 전용, {@link Bm25IndexInitializer} 참고).
 */
@Repository
@DependsOn("bm25IndexInitializer")
@ConditionalOnProperty(prefix = "search.hybrid", name = "enabled", havingValue = "true")
public class PlanHybridSearchRepository {

	private static final String SEARCH_SQL = """
			WITH qv AS (SELECT ?::vector AS v),
			vec AS (
			  SELECT id, row_number() OVER (ORDER BY d, id) AS r FROM (
			    SELECT p.id AS id, p.embedding <=> qv.v AS d FROM plans p, qv
			    WHERE p.status = 'ACTIVE' AND p.embedding IS NOT NULL
			    ORDER BY d LIMIT ?) s),
			kw AS (
			  SELECT id, row_number() OVER (ORDER BY sc DESC, id) AS r FROM (
			    SELECT id, pdb.score(id) AS sc FROM plans
			    WHERE status = 'ACTIVE'
			      AND (name ||| ?::text OR summary ||| ?::text OR description ||| ?::text)
			    ORDER BY sc DESC LIMIT ?) s),
			fused AS (
			  SELECT COALESCE(vec.id, kw.id) AS id, vec.r AS vr,
			         COALESCE(?::float8 / (?::int + vec.r), 0) + COALESCE(?::float8 / (?::int + kw.r), 0) AS rrf
			  FROM vec FULL OUTER JOIN kw ON vec.id = kw.id)
			SELECT p.id, p.plan_code, p.name, p.summary, p.monthly_fee, p.description, p.updated_at,
			       p.network_type, p.target_group, p.min_age, p.max_age,
			       p.data_policy, p.base_data_mb, p.exhausted_speed_kbps,
			       p.voice_policy, p.voice_minutes, p.sms_policy, p.sms_count,
			       1 - (p.embedding <=> qv.v) AS similarity
			FROM fused JOIN plans p ON p.id = fused.id CROSS JOIN qv
			WHERE p.embedding IS NOT NULL
			ORDER BY fused.rrf DESC, fused.vr ASC NULLS LAST, p.id
			LIMIT ?
			""";

	private final JdbcTemplate jdbcTemplate;

	public PlanHybridSearchRepository(DataSource dataSource) {
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
	public List<PlanSimilarityResult> search(
			float[] queryVector, String keywordText, HybridWeights weights, int candidateLimit, int poolSize) {

		ConnectionCallback<List<PlanSimilarityResult>> action = connection -> {
			PGvector.registerTypes(connection);
			PGvector vector = new PGvector(queryVector);

			try (PreparedStatement statement = connection.prepareStatement(SEARCH_SQL)) {
				int i = 1;
				statement.setObject(i++, vector);
				statement.setInt(i++, candidateLimit);
				statement.setString(i++, keywordText);
				statement.setString(i++, keywordText);
				statement.setString(i++, keywordText);
				statement.setInt(i++, candidateLimit);
				statement.setDouble(i++, weights.vector());
				statement.setInt(i++, weights.rrfK());
				statement.setDouble(i++, weights.keyword());
				statement.setInt(i++, weights.rrfK());
				statement.setInt(i, poolSize);

				List<PlanSimilarityResult> results = new ArrayList<>();
				try (ResultSet resultSet = statement.executeQuery()) {
					while (resultSet.next()) {
						results.add(PlanVectorSearchRepository.mapRow(resultSet));
					}
				}
				return results;
			}
		};

		return jdbcTemplate.execute(action);
	}
}
