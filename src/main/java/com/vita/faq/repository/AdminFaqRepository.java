package com.vita.faq.repository;

import com.pgvector.PGvector;
import com.vita.common.page.PageResponse;
import com.vita.embedding.EmbeddingConstants;
import com.vita.faq.dto.FaqItemResponse;
import com.vita.faq.dto.FaqListItemResponse;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.Optional;

/** 관리자 FAQ API에서 사용하는 JDBC 조회 및 변경 쿼리를 수행 */
@Repository
public class AdminFaqRepository {
    private static final String ITEM_COLUMNS = "id, category, subcategory, question, status, created_at";
    private static final String LIST_COLUMNS = "id, category, subcategory, question, answer, status, created_at, updated_at";
    private static final RowMapper<FaqItemResponse> ITEM_MAPPER = (rs, row) -> new FaqItemResponse(
        rs.getLong("id"), rs.getString("category"), rs.getString("subcategory"),
        rs.getString("question"), rs.getString("status"), rs.getObject("created_at", LocalDateTime.class));
    private static final RowMapper<FaqListItemResponse> LIST_MAPPER = (rs, row) -> new FaqListItemResponse(
        rs.getLong("id"), rs.getString("category"), rs.getString("subcategory"),
        rs.getString("question"), rs.getString("answer"), rs.getString("status"),
        rs.getObject("created_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class));
    private final NamedParameterJdbcTemplate jdbc;

    public AdminFaqRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public PageResponse<FaqListItemResponse> search(int page, int size, String keyword, String category,
            String status, String sortProperty, Sort.Direction direction) {
        var params = new MapSqlParameterSource().addValue("limit", size).addValue("offset", (long) page * size);
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (keyword != null) {
            // '%'와 '_'도 사용자가 입력한 문자 그대로 검색한다.
            where.append(" AND (question ILIKE :keyword ESCAPE '!' OR answer ILIKE :keyword ESCAPE '!')");
            params.addValue("keyword", "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        if (category != null) { where.append(" AND category = :category"); params.addValue("category", category); }
        if (status != null) { where.append(" AND status = :status"); params.addValue("status", status); }
        String orderColumn = switch (sortProperty) {
            case "createdAt" -> "created_at";
            case "updatedAt" -> "updated_at";
            default -> throw new IllegalArgumentException("허용되지 않는 정렬 필드입니다.");
        };
        String orderDirection = direction == Sort.Direction.ASC ? "ASC" : "DESC";
        String nullsOrder = "updatedAt".equals(sortProperty) ? " NULLS LAST" : "";
        long count = jdbc.queryForObject("SELECT count(*) FROM faqs" + where, params, Long.class);
        var rows = jdbc.query("SELECT " + LIST_COLUMNS + " FROM faqs" + where
            + " ORDER BY " + orderColumn + " " + orderDirection + nullsOrder
            + ", id " + orderDirection + " LIMIT :limit OFFSET :offset", params, LIST_MAPPER);
        return new PageResponse<>(rows, count, (int) ((count + size - 1) / size), page);
    }

    /** 수정과 삭제가 같은 행에 대해 직렬화되도록 트랜잭션 안에서 호출 */
    public Optional<FaqRecord> findForUpdate(long id) {
        return jdbc.query("SELECT id, category, subcategory, question, answer, status FROM faqs WHERE id=:id FOR UPDATE",
            new MapSqlParameterSource("id", id), (rs, row) -> new FaqRecord(rs.getLong("id"),
                rs.getString("category"), rs.getString("subcategory"), rs.getString("question"),
                rs.getString("answer"), rs.getString("status"))).stream().findFirst();
    }

    public FaqItemResponse create(FaqRecord faq, float[] vector, long adminId) {
        var params = fields(faq, adminId);
        embedding(params, vector);
        return jdbc.queryForObject("""
            INSERT INTO faqs (category, subcategory, question, answer, status, embedding,
                embedding_model, embedding_version, embedded_at, updated_by, updated_at)
            VALUES (:category, :subcategory, :question, :answer, 'ACTIVE', CAST(:vector AS vector),
                :model, :version, now(), :adminId, now())
            RETURNING
            """ + ITEM_COLUMNS, params, ITEM_MAPPER);
    }

    public LocalDateTime update(FaqRecord faq, float[] vector, long adminId) {
        var params = fields(faq, adminId);
        String vectorUpdate = "";
        if (vector != null) {
            embedding(params, vector);
            vectorUpdate = ", embedding=CAST(:vector AS vector), embedding_model=:model, embedding_version=:version, embedded_at=now()";
        }
        return jdbc.queryForObject("""
            UPDATE faqs SET category=:category, subcategory=:subcategory, question=:question,
                answer=:answer, status=:status, updated_by=:adminId, updated_at=clock_timestamp()
            """ + vectorUpdate + " WHERE id=:id RETURNING updated_at", params, LocalDateTime.class);
    }

    public void deactivate(long id, long adminId) {
        jdbc.update("UPDATE faqs SET status='INACTIVE', updated_by=:adminId, updated_at=clock_timestamp() WHERE id=:id",
            new MapSqlParameterSource("id", id).addValue("adminId", adminId));
    }

    private MapSqlParameterSource fields(FaqRecord faq, long adminId) {
        return new MapSqlParameterSource("id", faq.id()).addValue("category", faq.category())
            .addValue("subcategory", faq.subcategory()).addValue("question", faq.question())
            .addValue("answer", faq.answer()).addValue("status", faq.status()).addValue("adminId", adminId);
    }

    private void embedding(MapSqlParameterSource params, float[] vector) {
        params.addValue("vector", new PGvector(vector).toString())
            .addValue("model", EmbeddingConstants.MODEL_NAME).addValue("version", EmbeddingConstants.VERSION);
    }
}
