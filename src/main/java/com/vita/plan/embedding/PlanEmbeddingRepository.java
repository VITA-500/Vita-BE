package com.vita.plan.embedding;

import com.pgvector.PGvector;
import com.vita.embedding.EmbeddingConstants;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;

@Repository
public class PlanEmbeddingRepository {
    private final JdbcTemplate jdbcTemplate;

    public PlanEmbeddingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<PlanEmbeddingTarget> findPending(long afterId, int limit) {
        return jdbcTemplate.query("""
            SELECT id, description FROM plans
            WHERE status = 'ACTIVE' AND embedding IS NULL AND id > ?
            ORDER BY id LIMIT ?
            """, (rs, row) -> new PlanEmbeddingTarget(rs.getLong("id"), rs.getString("description")),
            afterId, limit);
    }

    public boolean saveIfPending(PlanEmbeddingTarget target, float[] vector) {
        if (vector == null || vector.length != EmbeddingConstants.DIMENSIONS) {
            throw new IllegalArgumentException("요금제 임베딩은 768차원이어야 합니다.");
        }
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("요금제 임베딩에 유한하지 않은 값이 있습니다.");
            }
        }
        Integer updated = jdbcTemplate.execute((ConnectionCallback<Integer>) connection -> {
            PGvector.registerTypes(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE plans
                SET embedding = ?, embedding_model = ?, embedding_version = ?,
                    embedded_at = now(), updated_at = now()
                WHERE id = ? AND status = 'ACTIVE' AND embedding IS NULL AND description = ?
                """)) {
                statement.setObject(1, new PGvector(vector));
                statement.setString(2, EmbeddingConstants.MODEL_NAME);
                statement.setString(3, PlanEmbeddingTarget.VERSION);
                statement.setLong(4, target.id());
                statement.setString(5, target.description());
                return statement.executeUpdate();
            }
        });
        return updated != null && updated == 1;
    }
}
