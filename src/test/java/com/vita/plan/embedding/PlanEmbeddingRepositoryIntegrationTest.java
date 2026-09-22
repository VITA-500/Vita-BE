package com.vita.plan.embedding;

import com.vita.embedding.EmbeddingConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.*;

/** 실제 PostgreSQL/pgvector 검증. 임시 테이블만 사용하며 기존 plans 데이터는 건드리지 않는다. */
@EnabledIfEnvironmentVariable(named = "PLAN_TEST_DB_URL", matches = ".+")
class PlanEmbeddingRepositoryIntegrationTest {
    @Test
    void persistsVectorAndMetadataAndProtectsConcurrentChanges() throws Exception {
        try (var connection = DriverManager.getConnection(System.getenv("PLAN_TEST_DB_URL"),
            System.getenv("PLAN_TEST_DB_USERNAME"), System.getenv("PLAN_TEST_DB_PASSWORD"))) {
            connection.setAutoCommit(false);
            try {
                var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                jdbc.execute("""
                    CREATE TEMP TABLE plans (id bigint PRIMARY KEY, description text, status text,
                    embedding vector(768), embedding_model text, embedding_version text,
                    embedded_at timestamp, updated_at timestamp) ON COMMIT DROP
                    """);
                jdbc.update("INSERT INTO plans(id,description,status) VALUES (1,'설명','ACTIVE'),(2,'제외','INACTIVE'),(3,'원문','ACTIVE')");
                var repository = new PlanEmbeddingRepository(jdbc);
                var targets = repository.findPending(0, 100);
                assertThat(targets).extracting(PlanEmbeddingTarget::id).containsExactly(1L, 3L);
                float[] vector = new float[768];
                vector[0] = 1;
                assertThat(repository.saveIfPending(targets.get(0), vector)).isTrue();
                assertThat(repository.saveIfPending(targets.get(0), vector)).isFalse();
                assertThat(jdbc.queryForObject("SELECT vector_dims(embedding) FROM plans WHERE id=1", Integer.class)).isEqualTo(768);
                assertThat(jdbc.queryForObject("SELECT embedding_model FROM plans WHERE id=1", String.class)).isEqualTo(EmbeddingConstants.MODEL_NAME);
                assertThat(jdbc.queryForObject("SELECT embedding_version FROM plans WHERE id=1", String.class)).isEqualTo("v1");
                assertThat(jdbc.queryForObject("SELECT embedded_at IS NOT NULL AND updated_at IS NOT NULL FROM plans WHERE id=1", Boolean.class)).isTrue();
                jdbc.update("UPDATE plans SET description='수정됨' WHERE id=3");
                assertThat(repository.saveIfPending(targets.get(1), vector)).isFalse();
                assertThat(repository.saveIfPending(new PlanEmbeddingTarget(2,"제외"), vector)).isFalse();
                assertThat(repository.findPending(1, 1)).extracting(PlanEmbeddingTarget::id).containsExactly(3L);
                assertThatThrownBy(() -> repository.saveIfPending(targets.get(1), new float[2])).isInstanceOf(IllegalArgumentException.class);
                vector[0] = Float.NaN;
                assertThatThrownBy(() -> repository.saveIfPending(targets.get(1), vector)).isInstanceOf(IllegalArgumentException.class);
            } finally {
                connection.rollback();
            }
        }
    }
}
