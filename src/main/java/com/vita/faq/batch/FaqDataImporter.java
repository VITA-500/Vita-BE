package com.vita.faq.batch;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.util.List;

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
		    status = 'ACTIVE',
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
		    updated_at = now()
		""";

	private final JdbcTemplate jdbcTemplate;

	public FaqDataImporter(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional
	public int importFaqs(List<FaqJsonlRecord> faqs) {
		return jdbcTemplate.execute((ConnectionCallback<Integer>) connection -> {
			try (PreparedStatement statement = connection.prepareStatement(UPSERT_SQL)) {
				int count = 0;
				for (FaqJsonlRecord faq : faqs) {
					Array policyIds = connection.createArrayOf("text", faq.sourcePolicyIds().toArray(String[]::new));
					try {
						statement.setString(1, faq.category());
						statement.setString(2, faq.subcategory());
						statement.setString(3, faq.question());
						statement.setString(4, faq.answer());
						statement.setString(5, faq.stableId());
						statement.setArray(6, policyIds);
						count += statement.executeUpdate();
					} finally {
						policyIds.free();
					}
				}
				return count;
			}
		});
	}
}
