package com.vita.faq.batch;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.util.List;

/**
 * 원본 식별자를 기준으로 FAQ를 적재한다.
 * 동일 데이터 재적재와 관리자 수정 행은 보존하고, 실제 원본 내용이 바뀐 시스템 관리 행만 갱신한다.
 */
@Service
public class FaqDataImporter {

	private static final String UPSERT_SQL = """
		INSERT INTO faqs (
		    category, subcategory, question, answer, status,
		    source_faq_id, source_policy_ids
		) VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)
		ON CONFLICT (source_faq_id) DO UPDATE SET
		    category = EXCLUDED.category,
		    subcategory = EXCLUDED.subcategory,
		    question = EXCLUDED.question,
		    answer = EXCLUDED.answer,
		    source_policy_ids = EXCLUDED.source_policy_ids,
		    embedding = CASE
		        WHEN faqs.question IS DISTINCT FROM EXCLUDED.question
		          OR faqs.answer IS DISTINCT FROM EXCLUDED.answer THEN NULL
		        ELSE faqs.embedding
		    END,
		    embedding_model = CASE
		        WHEN faqs.question IS DISTINCT FROM EXCLUDED.question
		          OR faqs.answer IS DISTINCT FROM EXCLUDED.answer THEN NULL
		        ELSE faqs.embedding_model
		    END,
		    embedding_version = CASE
		        WHEN faqs.question IS DISTINCT FROM EXCLUDED.question
		          OR faqs.answer IS DISTINCT FROM EXCLUDED.answer THEN NULL
		        ELSE faqs.embedding_version
		    END,
		    embedded_at = CASE
		        WHEN faqs.question IS DISTINCT FROM EXCLUDED.question
		          OR faqs.answer IS DISTINCT FROM EXCLUDED.answer THEN NULL
		        ELSE faqs.embedded_at
		    END,
		    updated_at = CASE
		        WHEN faqs.category IS DISTINCT FROM EXCLUDED.category
		          OR faqs.subcategory IS DISTINCT FROM EXCLUDED.subcategory
		          OR faqs.question IS DISTINCT FROM EXCLUDED.question
		          OR faqs.answer IS DISTINCT FROM EXCLUDED.answer THEN clock_timestamp()
		        ELSE faqs.updated_at
		    END,
		    updated_by = CASE
		        WHEN faqs.category IS DISTINCT FROM EXCLUDED.category
		          OR faqs.subcategory IS DISTINCT FROM EXCLUDED.subcategory
		          OR faqs.question IS DISTINCT FROM EXCLUDED.question
		          OR faqs.answer IS DISTINCT FROM EXCLUDED.answer THEN NULL
		        ELSE faqs.updated_by
		    END
		WHERE faqs.updated_by IS NULL
		  AND (faqs.category IS DISTINCT FROM EXCLUDED.category
		    OR faqs.subcategory IS DISTINCT FROM EXCLUDED.subcategory
		    OR faqs.question IS DISTINCT FROM EXCLUDED.question
		    OR faqs.answer IS DISTINCT FROM EXCLUDED.answer
		    OR faqs.source_policy_ids IS DISTINCT FROM EXCLUDED.source_policy_ids)
		""";

	private final JdbcTemplate jdbcTemplate;

	public FaqDataImporter(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional
	public int importFaqs(List<FaqJsonlRecord> faqs) {
		return jdbcTemplate.execute((ConnectionCallback<Integer>) connection -> {
			try (PreparedStatement statement = connection.prepareStatement(UPSERT_SQL)) {
				int changedCount = 0;
				for (FaqJsonlRecord faq : faqs) {
					Array policyIds = connection.createArrayOf("text", faq.sourcePolicyIds().toArray(String[]::new));
					try {
						statement.setString(1, faq.category());
						statement.setString(2, faq.subcategory());
						statement.setString(3, faq.question());
						statement.setString(4, faq.answer());
						statement.setString(5, faq.stableId());
						statement.setArray(6, policyIds);
						changedCount += statement.executeUpdate();
					} finally {
						policyIds.free();
					}
				}
				return changedCount;
			}
		});
	}
}
