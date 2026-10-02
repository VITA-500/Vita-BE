package com.vita.faq.batch;

import com.vita.embedding.EmbeddingConstants;
import com.vita.faq.embedding.FaqEmbeddingRepository;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 연결 전용 임시 테이블에서 재적재와 임베딩이 FAQ 변경 시각을 오염시키지 않는지 검증한다. */
@EnabledIfEnvironmentVariable(named = "FAQ_TEST_DB_URL", matches = ".+")
class FaqDataLifecycleIntegrationTest {

	@Test
	void changesTimestampOnlyForActualSourceChangesAndProtectsAdminUpdates() throws Exception {
		try (var connection = DriverManager.getConnection(
			System.getenv("FAQ_TEST_DB_URL"),
			System.getenv("FAQ_TEST_DB_USERNAME"),
			System.getenv("FAQ_TEST_DB_PASSWORD"))) {
			var dataSource = new SingleConnectionDataSource(connection, true);
			var jdbc = new JdbcTemplate(dataSource);
			jdbc.execute("""
				CREATE TEMP TABLE faqs (
				    id BIGSERIAL PRIMARY KEY,
				    category varchar(50) NOT NULL,
				    subcategory varchar(50),
				    question text NOT NULL,
				    answer text NOT NULL,
				    status varchar(20) NOT NULL,
				    embedding vector(768),
				    embedding_model varchar(100),
				    embedding_version varchar(30),
				    embedded_at timestamp,
				    updated_by bigint,
				    created_at timestamp NOT NULL DEFAULT now(),
				    updated_at timestamp,
				    source_faq_id varchar(100) UNIQUE,
				    source_policy_ids text[] NOT NULL DEFAULT '{}'
				)
				""");

			var importer = new FaqDataImporter(jdbc);
			var embeddingRepository = new FaqEmbeddingRepository(jdbc);
			var original = faq("원본 질문", "원본 답변", List.of("P-1"));

			assertThat(importer.importFaqs(List.of(original))).isEqualTo(1);
			long faqId = jdbc.queryForObject(
				"SELECT id FROM faqs WHERE source_faq_id='FAQ-1'", Long.class);
			LocalDateTime originalUpdatedAt = LocalDateTime.of(2026, 9, 1, 10, 0);
			jdbc.update("UPDATE faqs SET updated_at=? WHERE id=?", originalUpdatedAt, faqId);

			float[] firstVector = new float[EmbeddingConstants.DIMENSIONS];
			firstVector[0] = 1;
			assertThat(embeddingRepository.saveIfPending(faqId, firstVector)).isTrue();
			assertThat(updatedAt(jdbc, faqId)).isEqualTo(originalUpdatedAt);
			assertThat(jdbc.queryForObject(
				"SELECT embedded_at IS NOT NULL FROM faqs WHERE id=?", Boolean.class, faqId)).isTrue();
			String originalEmbedding = embedding(jdbc, faqId);

			assertThat(importer.importFaqs(List.of(original))).isZero();
			assertThat(updatedAt(jdbc, faqId)).isEqualTo(originalUpdatedAt);
			assertThat(embedding(jdbc, faqId)).isEqualTo(originalEmbedding);

			var policyOnlyChanged = faq("원본 질문", "원본 답변", List.of("P-1", "P-2"));
			assertThat(importer.importFaqs(List.of(policyOnlyChanged))).isEqualTo(1);
			assertThat(updatedAt(jdbc, faqId)).isEqualTo(originalUpdatedAt);
			assertThat(embedding(jdbc, faqId)).isEqualTo(originalEmbedding);

			var sourceChanged = faq("변경 질문", "변경 답변", List.of("P-1", "P-2"));
			assertThat(importer.importFaqs(List.of(sourceChanged))).isEqualTo(1);
			LocalDateTime sourceUpdatedAt = updatedAt(jdbc, faqId);
			assertThat(sourceUpdatedAt).isAfter(originalUpdatedAt);
			assertThat(jdbc.queryForObject(
				"SELECT embedding IS NULL FROM faqs WHERE id=?", Boolean.class, faqId)).isTrue();
			assertThat(jdbc.queryForObject(
				"SELECT updated_by FROM faqs WHERE id=?", Long.class, faqId)).isNull();

			float[] secondVector = new float[EmbeddingConstants.DIMENSIONS];
			secondVector[1] = 1;
			assertThat(embeddingRepository.saveIfPending(faqId, secondVector)).isTrue();
			assertThat(updatedAt(jdbc, faqId)).isEqualTo(sourceUpdatedAt);

			LocalDateTime adminUpdatedAt = LocalDateTime.of(2026, 9, 2, 10, 0);
			jdbc.update("""
				UPDATE faqs
				SET question='관리자 질문', status='INACTIVE', updated_by=9, updated_at=?
				WHERE id=?
				""", adminUpdatedAt, faqId);

			var laterSource = faq("다시 변경된 원본 질문", "다시 변경된 원본 답변", List.of("P-3"));
			assertThat(importer.importFaqs(List.of(laterSource))).isZero();
			assertThat(jdbc.queryForObject(
				"SELECT question FROM faqs WHERE id=?", String.class, faqId)).isEqualTo("관리자 질문");
			assertThat(jdbc.queryForObject(
				"SELECT status FROM faqs WHERE id=?", String.class, faqId)).isEqualTo("INACTIVE");
			assertThat(updatedAt(jdbc, faqId)).isEqualTo(adminUpdatedAt);
		}
	}

	private FaqJsonlRecord faq(String question, String answer, List<String> policyIds) {
		return new FaqJsonlRecord("FAQ-1", "모바일", "요금제", question, answer, policyIds);
	}

	private LocalDateTime updatedAt(JdbcTemplate jdbc, long faqId) {
		return jdbc.queryForObject(
			"SELECT updated_at FROM faqs WHERE id=?", LocalDateTime.class, faqId);
	}

	private String embedding(JdbcTemplate jdbc, long faqId) {
		return jdbc.queryForObject(
			"SELECT embedding::text FROM faqs WHERE id=?", String.class, faqId);
	}
}
