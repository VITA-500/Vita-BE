package com.vita.faq.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vita.common.exception.BusinessException;
import com.vita.common.page.PageRequest;
import com.vita.embedding.EmbeddingException;
import com.vita.embedding.EmbeddingProvider;
import com.vita.faq.dto.*;
import com.vita.faq.repository.AdminFaqRepository;
import com.vita.faq.service.AdminFaqService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 연결 전용 임시 faqs 테이블만 사용한다. 로컬/공유 DB의 실제 FAQ에는 접근하지 않는다. */
@EnabledIfEnvironmentVariable(named="FAQ_TEST_DB_URL", matches=".+")
class AdminFaqRepositoryIntegrationTest {
    @Test void crudFiltersEmbeddingAndRollbackOnRealPgvector() throws Exception {
        try (var connection = DriverManager.getConnection(System.getenv("FAQ_TEST_DB_URL"),
            System.getenv("FAQ_TEST_DB_USERNAME"), System.getenv("FAQ_TEST_DB_PASSWORD"))) {
            var ds = new SingleConnectionDataSource(connection,true);
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute("""
                CREATE TEMP TABLE faqs (
                    id BIGSERIAL PRIMARY KEY, category varchar(50) NOT NULL, subcategory varchar(50),
                    question text NOT NULL, answer text NOT NULL,
                    status varchar(20) NOT NULL CHECK (status IN ('ACTIVE','INACTIVE')),
                    embedding vector(768), embedding_model varchar(100), embedding_version varchar(30),
                    embedded_at timestamp, updated_by bigint,
                    created_at timestamp NOT NULL DEFAULT now(), updated_at timestamp,
                    source_faq_id varchar(100), source_policy_ids text[] NOT NULL DEFAULT '{}')
                """);
            var provider = mock(EmbeddingProvider.class);
            float[] firstVector = new float[768]; firstVector[0] = 1;
            when(provider.embedDocument(anyString())).thenReturn(firstVector);
            var service = new AdminFaqService(new AdminFaqRepository(new NamedParameterJdbcTemplate(ds)),provider);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
            var mapper = new ObjectMapper();
            var created = tx.execute(s -> service.create(new FaqCreateRequest("모바일","요금제","질문","할인 50% _ 안내"),9));
            long id = created.faqId();
            assertThat(created.status()).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("SELECT vector_dims(embedding) FROM faqs WHERE id=?",Integer.class,id)).isEqualTo(768);
            assertThat(jdbc.queryForObject("SELECT embedding_version FROM faqs WHERE id=?",String.class,id)).isEqualTo("v2");
            assertThat(jdbc.queryForObject("SELECT embedding_model FROM faqs WHERE id=?",String.class,id)).isEqualTo("intfloat/multilingual-e5-base");
            assertThat(jdbc.queryForObject("SELECT updated_by FROM faqs WHERE id=?",Long.class,id)).isEqualTo(9);
            assertThat(jdbc.queryForObject("SELECT cardinality(source_policy_ids) FROM faqs WHERE id=?",Integer.class,id)).isZero();
            var second = tx.execute(s -> service.create(new FaqCreateRequest("해외로밍",null,"다른 질문","답변"),9));
            jdbc.update("UPDATE faqs SET updated_at=? WHERE id=?", LocalDateTime.of(2026,9,23,10,0), id);
            jdbc.update("UPDATE faqs SET updated_at=? WHERE id=?", LocalDateTime.of(2026,9,23,11,0), second.faqId());
            assertThat(service.list(PageRequest.of(0,1,null,null),null,null).totalCount()).isEqualTo(2);
            assertThat(service.list(PageRequest.of(0,1,null,null),null,null).totalPages()).isEqualTo(2);
            var newest = service.list(PageRequest.of(0,20,null,"updatedAt,desc"),null,null).content().getFirst();
            assertThat(newest.faqId()).isEqualTo(second.faqId());
            assertThat(newest.answer()).isEqualTo("답변");
            assertThat(newest.updatedAt()).isEqualTo(LocalDateTime.of(2026,9,23,11,0));
            assertThat(service.list(PageRequest.of(0,20,null,"updatedAt,asc"),null,null).content().getFirst().faqId())
                .isEqualTo(id);
            assertThat(service.list(PageRequest.of(0,20,"50%",null),null,null).totalCount()).isEqualTo(1);
            assertThat(service.list(PageRequest.of(0,20,"_",null),null,null).totalCount()).isEqualTo(1);
            assertThat(service.list(PageRequest.of(0,20,"' OR 1=1 --",null),null,null).totalCount()).isZero();
            assertThat(service.list(PageRequest.of(0,20,null,null),"모바일",null).totalCount()).isEqualTo(1);
            assertThat(service.list(PageRequest.of(10,20,null,null),null,null).content()).isEmpty();

            String before = jdbc.queryForObject("SELECT embedding::text FROM faqs WHERE id=?",String.class,id);
            reset(provider);
            tx.executeWithoutResult(s -> service.delete(id,10));
            tx.executeWithoutResult(s -> service.delete(id,10));
            assertThat(service.list(PageRequest.of(0,20,null,null),null,null).totalCount()).isEqualTo(2);
            assertThat(service.list(PageRequest.of(0,20,null,null),null,"INACTIVE").totalCount()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM faqs WHERE status='ACTIVE' AND id=?",Long.class,id)).isZero();
            assertThat(jdbc.queryForObject("SELECT embedding::text FROM faqs WHERE id=?",String.class,id)).isEqualTo(before);
            var restore = mapper.readValue("{\"status\":\"ACTIVE\",\"subcategory\":null}",FaqUpdateRequest.class);
            tx.executeWithoutResult(s -> service.update(id,restore,11));
            assertThat(jdbc.queryForObject("SELECT subcategory FROM faqs WHERE id=?",String.class,id)).isNull();
            verifyNoInteractions(provider);

            var changed = mapper.readValue("{\"answer\":\"변경 답변\"}",FaqUpdateRequest.class);
            when(provider.embedDocument(anyString())).thenThrow(new EmbeddingException("unavailable"));
            assertThatThrownBy(() -> tx.execute(s -> service.update(id,changed,12))).isInstanceOf(BusinessException.class);
            assertThat(jdbc.queryForObject("SELECT answer FROM faqs WHERE id=?",String.class,id)).isEqualTo("할인 50% _ 안내");
            assertThat(jdbc.queryForObject("SELECT embedding::text FROM faqs WHERE id=?",String.class,id)).isEqualTo(before);
            long count = jdbc.queryForObject("SELECT count(*) FROM faqs",Long.class);
            assertThatThrownBy(() -> tx.execute(s -> service.create(new FaqCreateRequest("모바일",null,"실패","답변"),9)))
                .isInstanceOf(BusinessException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM faqs",Long.class)).isEqualTo(count);
            reset(provider);
            float[] secondVector = new float[768]; secondVector[1] = 1;
            when(provider.embedDocument(anyString())).thenReturn(secondVector);
            tx.executeWithoutResult(s -> service.update(id,changed,12));
            assertThat(jdbc.queryForObject("SELECT embedding::text FROM faqs WHERE id=?",String.class,id)).isNotEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT answer FROM faqs WHERE id=?",String.class,id)).isEqualTo("변경 답변");
            assertThat(jdbc.queryForObject("SELECT updated_by FROM faqs WHERE id=?",Long.class,id)).isEqualTo(12);

            // 저장 이후 오류 발생 시에도 같은 트랜잭션의 변경 전체가 롤백되는지 확인한다.
            assertThatThrownBy(() -> tx.execute(s -> {
                service.delete(id,13);
                throw new IllegalStateException("rollback check");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.queryForObject("SELECT status FROM faqs WHERE id=?",String.class,id)).isEqualTo("ACTIVE");
        }
    }
}
