package com.vita.faq.embedding;

import com.pgvector.PGvector;
import com.vita.embedding.EmbeddingConstants;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;

@Repository
public class FaqEmbeddingRepository {

	private static final String FIND_PENDING_SQL = """
		SELECT id, question, answer
		FROM faq
		WHERE status = 'ACTIVE'
		  AND embedding IS NULL
		ORDER BY id
		LIMIT ?
		""";

	private static final String UPDATE_EMBEDDING_SQL = """
		UPDATE faq
		SET embedding = ?,
		    embedding_model = ?,
		    embedding_version = ?,
		    embedded_at = now(),
		    updated_at = now()
		WHERE id = ?
		  AND status = 'ACTIVE'
		  AND embedding IS NULL
		""";

	private final JdbcTemplate jdbcTemplate;

	public FaqEmbeddingRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public List<FaqEmbeddingTarget> findPending(int limit) {
		return jdbcTemplate.query(
			FIND_PENDING_SQL,
			(resultSet, rowNumber) -> new FaqEmbeddingTarget(
				resultSet.getLong("id"),
				resultSet.getString("question"),
				resultSet.getString("answer")
			),
			limit
		);
	}

	public boolean saveIfPending(long faqId, float[] vector) {
		if (vector == null || vector.length != EmbeddingConstants.DIMENSIONS) {
			throw new IllegalArgumentException("FAQ 임베딩은 768차원이어야 합니다.");
		}
		Integer updated = jdbcTemplate.execute((ConnectionCallback<Integer>) connection -> {
			PGvector.registerTypes(connection);
			try (PreparedStatement statement = connection.prepareStatement(UPDATE_EMBEDDING_SQL)) {
				statement.setObject(1, new PGvector(vector));
				statement.setString(2, EmbeddingConstants.MODEL_NAME);
				statement.setString(3, EmbeddingConstants.VERSION);
				statement.setLong(4, faqId);
				return statement.executeUpdate();
			}
		});
		return updated != null && updated == 1;
	}
}
