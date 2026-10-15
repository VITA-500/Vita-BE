package com.vita.plan.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vita.common.exception.BusinessException;
import com.vita.common.page.PageRequest;
import com.vita.embedding.EmbeddingException;
import com.vita.embedding.EmbeddingProvider;
import com.vita.plan.dto.PlanCreateRequest;
import com.vita.plan.dto.PlanUpdateRequest;
import com.vita.plan.repository.AdminPlanRepository;
import com.vita.plan.service.AdminPlanService;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** 연결 전용 임시 plans 테이블만 사용하며 로컬 및 공유 DB의 실제 요금제에는 접근하지 않는다. */
@EnabledIfEnvironmentVariable(named = "PLAN_TEST_DB_URL", matches = ".+")
class AdminPlanRepositoryIntegrationTest {

    @Test
    void crudSortEmbeddingAndRollbackOnRealPgvector() throws Exception {
        try (var connection = DriverManager.getConnection(System.getenv("PLAN_TEST_DB_URL"),
                System.getenv("PLAN_TEST_DB_USERNAME"), System.getenv("PLAN_TEST_DB_PASSWORD"))) {
            var dataSource = new SingleConnectionDataSource(connection, true);
            var jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("""
                CREATE TEMP TABLE plans (
                    id BIGSERIAL PRIMARY KEY, plan_code varchar(50) NOT NULL UNIQUE,
                    name varchar(100) NOT NULL, summary varchar(255) NOT NULL,
                    monthly_fee int NOT NULL, network_type varchar(20) NOT NULL,
                    target_group varchar(20) NOT NULL, min_age int, max_age int,
                    data_policy varchar(20) NOT NULL, base_data_mb bigint,
                    exhausted_speed_kbps int, voice_policy varchar(20) NOT NULL,
                    voice_minutes int, sms_policy varchar(20) NOT NULL, sms_count int,
                    description text NOT NULL, status varchar(20) NOT NULL,
                    embedding vector(768), embedding_model varchar(100), embedding_version varchar(30),
                    embedded_at timestamp, created_at timestamp NOT NULL DEFAULT now(), updated_at timestamp)
                """);

            var provider = mock(EmbeddingProvider.class);
            float[] firstVector = new float[768]; firstVector[0] = 1;
            when(provider.embedDocument(anyString())).thenReturn(firstVector);
            var service = new AdminPlanService(
                new AdminPlanRepository(new NamedParameterJdbcTemplate(dataSource)), provider);
            var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            var mapper = new ObjectMapper();

            var expensive = transaction.execute(status -> service.create(request(
                "VITA-HIGH", "고가 요금제", 50000, "할인 50% _ 설명")));
            var cheap = transaction.execute(status -> service.create(request(
                "VITA-LOW", "저가 요금제", 20000, "저렴한 설명")));
            long expensiveId = expensive.planId();

            assertThat(jdbc.queryForObject("SELECT vector_dims(embedding) FROM plans WHERE id=?",
                Integer.class, expensiveId)).isEqualTo(768);
            assertThat(jdbc.queryForObject("SELECT embedding_version FROM plans WHERE id=?",
                String.class, expensiveId)).isEqualTo("v1");
            assertThat(service.list(PageRequest.of(0, 20, null, "price,asc")).content().getFirst().planId())
                .isEqualTo(cheap.planId());
            assertThat(service.list(PageRequest.of(0, 1, null, null)).totalPages()).isEqualTo(2);
            assertThat(service.list(PageRequest.of(0, 20, "50%", null)).totalCount()).isEqualTo(1);
            assertThat(service.list(PageRequest.of(0, 20, "_", null)).totalCount()).isEqualTo(1);

            String before = jdbc.queryForObject("SELECT embedding::text FROM plans WHERE id=?",
                String.class, expensiveId);
            transaction.executeWithoutResult(status -> service.delete(expensiveId));
            assertThat(jdbc.queryForObject("SELECT status FROM plans WHERE id=?", String.class, expensiveId))
                .isEqualTo("INACTIVE");
            assertThat(jdbc.queryForObject("SELECT embedding::text FROM plans WHERE id=?", String.class, expensiveId))
                .isEqualTo(before);
            assertThat(service.list(PageRequest.of(0, 20, null, null)).totalCount()).isEqualTo(2);
            jdbc.update("UPDATE plans SET updated_at=NULL WHERE id=?", cheap.planId());
            assertThat(service.list(PageRequest.of(0, 20, null, "updatedAt,asc")).content().getFirst().planId())
                .isEqualTo(expensiveId);

            PlanUpdateRequest restore = mapper.readValue("{\"status\":\"ACTIVE\"}", PlanUpdateRequest.class);
            reset(provider);
            transaction.executeWithoutResult(status -> service.update(expensiveId, restore));
            assertThat(jdbc.queryForObject("SELECT status FROM plans WHERE id=?", String.class, expensiveId))
                .isEqualTo("ACTIVE");

            PlanUpdateRequest changed = mapper.readValue(
                "{\"description\":\"변경된 설명\"}", PlanUpdateRequest.class);
            when(provider.embedDocument(anyString())).thenThrow(new EmbeddingException("unavailable"));
            assertThatThrownBy(() -> transaction.execute(status -> service.update(expensiveId, changed)))
                .isInstanceOf(BusinessException.class);
            assertThat(jdbc.queryForObject("SELECT description FROM plans WHERE id=?", String.class, expensiveId))
                .isEqualTo("할인 50% _ 설명");
            assertThat(jdbc.queryForObject("SELECT embedding::text FROM plans WHERE id=?", String.class, expensiveId))
                .isEqualTo(before);

            reset(provider);
            float[] secondVector = new float[768]; secondVector[1] = 1;
            when(provider.embedDocument(anyString())).thenReturn(secondVector);
            transaction.executeWithoutResult(status -> service.update(expensiveId, changed));
            assertThat(jdbc.queryForObject("SELECT description FROM plans WHERE id=?", String.class, expensiveId))
                .isEqualTo("변경된 설명");
            assertThat(jdbc.queryForObject("SELECT embedding::text FROM plans WHERE id=?", String.class, expensiveId))
                .isNotEqualTo(before);
        }
    }

    private PlanCreateRequest request(String code, String name, int price, String description) {
        return new PlanCreateRequest(code, name, "요약", price, "5G", "GENERAL",
            null, null, "LIMITED", 6144L, null, "UNLIMITED", null,
            "UNLIMITED", null, description);
    }
}
