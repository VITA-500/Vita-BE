package com.vita.faq.repository;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 채팅 등에서 사용하는 FAQ 원본 식별자 조회. */
@Repository
public class FaqRepository {

    private static final String FIND_ALL_ID_AND_SOURCE_FAQ_ID_SQL = """
        SELECT id, source_faq_id
        FROM faqs
        ORDER BY id
        """;

    private final JdbcTemplate jdbcTemplate;

    public FaqRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 각 행의 [0]은 Long DB ID, [1]은 String 원본 FAQ 코드이며 원본이 없으면 null이다. */
    public List<Object[]> findAllIdAndSourceFaqId() {
        return jdbcTemplate.query(
            FIND_ALL_ID_AND_SOURCE_FAQ_ID_SQL,
            (resultSet, rowNumber) -> new Object[] {
                resultSet.getLong("id"),
                resultSet.getString("source_faq_id")
            }
        );
    }
}
