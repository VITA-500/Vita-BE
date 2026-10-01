package com.vita.plan.repository;

import com.pgvector.PGvector;
import com.vita.common.page.PageResponse;
import com.vita.embedding.EmbeddingConstants;
import com.vita.plan.dto.PlanItemResponse;
import com.vita.plan.embedding.PlanEmbeddingTarget;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 관리자 요금제 API에서 사용하는 JDBC 조회 및 변경 쿼리를 수행한다. */
@Repository
public class AdminPlanRepository {

    private static final String COLUMNS = """
        id, plan_code, name, summary, monthly_fee, network_type, target_group,
        min_age, max_age, data_policy, base_data_mb, exhausted_speed_kbps,
        voice_policy, voice_minutes, sms_policy, sms_count, description, status,
        created_at, updated_at
        """;

    private static final RowMapper<PlanRecord> RECORD_MAPPER = (rs, row) -> new PlanRecord(
        rs.getLong("id"), rs.getString("plan_code"), rs.getString("name"), rs.getString("summary"),
        rs.getInt("monthly_fee"), rs.getString("network_type"), rs.getString("target_group"),
        rs.getObject("min_age", Integer.class), rs.getObject("max_age", Integer.class),
        rs.getString("data_policy"), rs.getObject("base_data_mb", Long.class),
        rs.getObject("exhausted_speed_kbps", Integer.class), rs.getString("voice_policy"),
        rs.getObject("voice_minutes", Integer.class), rs.getString("sms_policy"),
        rs.getObject("sms_count", Integer.class), rs.getString("description"), rs.getString("status"),
        rs.getObject("created_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class));

    private static final RowMapper<PlanItemResponse> ITEM_MAPPER = (rs, row) -> toResponse(RECORD_MAPPER.mapRow(rs, row));

    private final NamedParameterJdbcTemplate jdbc;

    public AdminPlanRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PageResponse<PlanItemResponse> search(
            int page, int size, String keyword, String sortProperty, Sort.Direction direction) {
        var params = new MapSqlParameterSource()
            .addValue("limit", size)
            .addValue("offset", (long) page * size);
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (keyword != null) {
            where.append(" AND (plan_code ILIKE :keyword ESCAPE '!' OR name ILIKE :keyword ESCAPE '!'"
                + " OR summary ILIKE :keyword ESCAPE '!' OR description ILIKE :keyword ESCAPE '!')");
            params.addValue("keyword", "%" + escapeLike(keyword) + "%");
        }

        String orderColumn = switch (sortProperty) {
            case "createdAt" -> "created_at";
            case "updatedAt" -> "updated_at";
            case "price" -> "monthly_fee";
            default -> throw new IllegalArgumentException("허용되지 않는 정렬 필드입니다.");
        };
        String orderDirection = direction == Sort.Direction.ASC ? "ASC" : "DESC";
        String nullsOrder = "updatedAt".equals(sortProperty) ? " NULLS LAST" : "";
        long count = jdbc.queryForObject("SELECT count(*) FROM plans" + where, params, Long.class);
        var content = jdbc.query("SELECT " + COLUMNS + " FROM plans" + where
            + " ORDER BY " + orderColumn + " " + orderDirection + nullsOrder
            + ", id ASC LIMIT :limit OFFSET :offset",
            params, ITEM_MAPPER);
        return new PageResponse<>(content, count, (int) ((count + size - 1) / size), page);
    }

    /** 수정과 삭제가 같은 행에 대해 직렬화되도록 트랜잭션 안에서 호출한다. */
    public Optional<PlanRecord> findForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM plans WHERE id=:id FOR UPDATE",
            new MapSqlParameterSource("id", id), RECORD_MAPPER).stream().findFirst();
    }

    public boolean existsByPlanCodeExcludingId(String planCode, long excludedId) {
        Long count = jdbc.queryForObject(
            "SELECT count(*) FROM plans WHERE plan_code=:planCode AND id<>:excludedId",
            new MapSqlParameterSource("planCode", planCode).addValue("excludedId", excludedId), Long.class);
        return count != null && count > 0;
    }

    public PlanItemResponse create(PlanRecord plan, float[] vector) {
        var params = fields(plan);
        embedding(params, vector);
        return jdbc.queryForObject("""
            INSERT INTO plans (
                plan_code, name, summary, monthly_fee, network_type, target_group,
                min_age, max_age, data_policy, base_data_mb, exhausted_speed_kbps,
                voice_policy, voice_minutes, sms_policy, sms_count, description, status,
                embedding, embedding_model, embedding_version, embedded_at, updated_at)
            VALUES (
                :planCode, :name, :summary, :price, :networkType, :targetGroup,
                :minAge, :maxAge, :dataPolicy, :baseDataMb, :exhaustedSpeedKbps,
                :voicePolicy, :voiceMinutes, :smsPolicy, :smsCount, :description, 'ACTIVE',
                CAST(:vector AS vector), :model, :version, now(), now())
            RETURNING
            """ + COLUMNS, params, ITEM_MAPPER);
    }

    public LocalDateTime update(PlanRecord plan, float[] vector) {
        var params = fields(plan);
        String vectorUpdate = "";
        if (vector != null) {
            embedding(params, vector);
            vectorUpdate = ", embedding=CAST(:vector AS vector), embedding_model=:model,"
                + " embedding_version=:version, embedded_at=now()";
        }
        return jdbc.queryForObject("""
            UPDATE plans SET
                plan_code=:planCode, name=:name, summary=:summary, monthly_fee=:price,
                network_type=:networkType, target_group=:targetGroup, min_age=:minAge,
                max_age=:maxAge, data_policy=:dataPolicy, base_data_mb=:baseDataMb,
                exhausted_speed_kbps=:exhaustedSpeedKbps, voice_policy=:voicePolicy,
                voice_minutes=:voiceMinutes, sms_policy=:smsPolicy, sms_count=:smsCount,
                description=:description, status=:status, updated_at=clock_timestamp()
            """ + vectorUpdate + " WHERE id=:id RETURNING updated_at", params, LocalDateTime.class);
    }

    public void deactivate(long id) {
        jdbc.update("UPDATE plans SET status='INACTIVE', updated_at=clock_timestamp() WHERE id=:id",
            new MapSqlParameterSource("id", id));
    }

    private MapSqlParameterSource fields(PlanRecord plan) {
        return new MapSqlParameterSource("id", plan.id())
            .addValue("planCode", plan.planCode()).addValue("name", plan.name())
            .addValue("summary", plan.summary()).addValue("price", plan.price())
            .addValue("networkType", plan.networkType()).addValue("targetGroup", plan.targetGroup())
            .addValue("minAge", plan.minAge()).addValue("maxAge", plan.maxAge())
            .addValue("dataPolicy", plan.dataPolicy()).addValue("baseDataMb", plan.baseDataMb())
            .addValue("exhaustedSpeedKbps", plan.exhaustedSpeedKbps())
            .addValue("voicePolicy", plan.voicePolicy()).addValue("voiceMinutes", plan.voiceMinutes())
            .addValue("smsPolicy", plan.smsPolicy()).addValue("smsCount", plan.smsCount())
            .addValue("description", plan.description()).addValue("status", plan.status());
    }

    private void embedding(MapSqlParameterSource params, float[] vector) {
        params.addValue("vector", new PGvector(vector).toString())
            .addValue("model", EmbeddingConstants.MODEL_NAME)
            .addValue("version", PlanEmbeddingTarget.VERSION);
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static PlanItemResponse toResponse(PlanRecord plan) {
        return new PlanItemResponse(plan.id(), plan.planCode(), plan.name(), plan.summary(), plan.price(),
            plan.networkType(), plan.targetGroup(), plan.minAge(), plan.maxAge(), plan.dataPolicy(),
            plan.baseDataMb(), plan.exhaustedSpeedKbps(), plan.voicePolicy(), plan.voiceMinutes(),
            plan.smsPolicy(), plan.smsCount(), plan.description(), plan.status(),
            plan.createdAt(), plan.updatedAt());
    }
}
