package com.vita.store.repository;

import com.vita.store.entity.Store;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

// 관리자 목록 검색은 StoreSpecs 조건 조합으로 한다 (JpaSpecificationExecutor)
public interface StoreRepository extends JpaRepository<Store, Long>, JpaSpecificationExecutor<Store> {

    @Query(value = """
            SELECT s.id, s.name, s.address, s.lat, s.lng, 
                to_char(s.open_time, 'HH24:MI') || '-' || to_char(s.close_time, 'HH24:MI') AS business_hours,
                s.phone, array_to_string(s.consult_services, '||') AS consult_services,
                array_to_string(s.provided_services, '||') AS provided_services,
                2 * 6371 * asin(sqrt(
                    power(sin(radians(s.lat - :lat) / 2), 2)
                    + cos(radians(:lat)) * cos(radians(s.lat)) *
                    power(sin(radians(s.lng - :lng) / 2), 2)
                    )) AS distance_km
            FROM stores s
            WHERE s.store_type = 'PHONE'
            ORDER BY distance_km
            LIMIT 1
            """, nativeQuery = true)
    Optional<StoreDistanceProjection> findNearest(@Param("lat")BigDecimal lat, @Param("lng") BigDecimal lng);

    @Query(value = """
            SELECT * FROM(
                SELECT s.id, s.name, s.address, s.lat, s.lng, 
                to_char(s.open_time, 'HH24:MI') || '-' || to_char(s.close_time, 'HH24:MI') AS business_hours,
                s.phone, array_to_string(s.consult_services, '||') AS consult_services,
                array_to_string(s.provided_services, '||') AS provided_services,
                2 * 6371 * asin(sqrt(
                    power(sin(radians(s.lat - :lat) / 2), 2)
                    + cos(radians(:lat)) * cos(radians(s.lat)) *
                    power(sin(radians(s.lng - :lng) / 2), 2)
                    )) AS distance_km
                FROM stores s
                WHERE s.store_type = 'PHONE'
                AND (CAST(:openAt AS TIME) IS NULL OR CASE
                            WHEN s.open_time < s.close_time
                                THEN CAST(:openAt AS TIME) >= s.open_time AND
                                     CAST(:openAt AS TIME) < s.close_time
                                ELSE CAST(:openAt AS TIME) >= s.open_time OR 
                                     CAST(:openAt AS TIME) < s.close_time 
                                END)
            ) ranked
            WHERE distance_km <= :radiusKm
            ORDER BY distance_km
            """, nativeQuery = true)

    List<StoreDistanceProjection> findNearBy(
            @Param("lat") BigDecimal lat, @Param("lng") BigDecimal lng,
            @Param("radiusKm") double radiusKm, @Param("openAt") LocalTime openAt);

    /**
     * 채팅용 주변 통신 매장.
     * 요청한 서비스를 많이 제공하는 매장 먼저, 같으면 가까운 순
     */
    @Query(value = """
            SELECT * FROM(
                SELECT s.id, s.name, s.address, s.lat, s.lng,
                to_char(s.open_time, 'HH24:MI') || '-' || to_char(s.close_time, 'HH24:MI') AS business_hours,
                s.phone, array_to_string(s.consult_services, '||') AS consult_services,
                array_to_string(s.provided_services, '||') AS provided_services,
                2 * 6371 * asin(sqrt(
                    power(sin(radians(s.lat - :lat) / 2), 2)
                    + cos(radians(:lat)) * cos(radians(s.lat)) *
                    power(sin(radians(s.lng - :lng) / 2), 2)
                    )) AS distance_km,
                (SELECT count(*)
                 FROM unnest(string_to_array(CAST(:services AS TEXT), '||')) AS requested(service)
                 WHERE requested.service = ANY(s.consult_services || s.provided_services)) AS matched_count
                FROM stores s
                WHERE s.store_type = 'PHONE'
                AND (CAST(:openAt AS TIME) IS NULL OR CASE
                            WHEN s.open_time < s.close_time
                                THEN CAST(:openAt AS TIME) >= s.open_time AND
                                     CAST(:openAt AS TIME) < s.close_time
                                ELSE CAST(:openAt AS TIME) >= s.open_time OR 
                                     CAST(:openAt AS TIME) < s.close_time 
                                END)
            ) ranked
            WHERE distance_km <= :radiusKm
            ORDER BY matched_count DESC, distance_km, id
            LIMIT :limit
            """, nativeQuery = true)
    List<StoreDistanceProjection> findNearbyForChat(
            @Param("lat") BigDecimal lat,
            @Param("lng") BigDecimal lng,
            @Param("radiusKm") double radiusKm,
            @Param("services") String services,
            @Param("openAt") LocalTime openAt,
            @Param("limit") int limit);

    /** 업종별 제휴 매장 거리순, 업종 및 브랜드는 연결을 따라 조인한다.
     * category·benefitId·radiusKm·openAt은 선택이라 null이면 조건을 건너뛴다
     */
    @Query(value = """
            SELECT *
            FROM(
                SELECT s.id, s.name, s.address, s.phone, s.lat, s.lng,
                   to_char(s.open_time, 'HH24:MI') || '-' || to_char(s.close_time, 'HH24:MI') AS business_hours,
                   b.id AS benefit_id, b.brand, b.category, b.name AS benefit_name, b.description AS benefit_description,
                   2 * 6371 * asin(sqrt(
                       power(sin(radians(s.lat - :lat) / 2), 2)
                       + cos(radians(:lat)) * cos(radians(s.lat)) *
                       power(sin(radians(s.lng - :lng) / 2), 2)
                       )) AS distance_km
                FROM stores s
                JOIN store_benefits sb ON sb.store_id = s.id
                JOIN benefits b ON b.id = sb.benefit_id
                WHERE s.store_type = 'PARTNER'
                    AND (CAST(:category AS VARCHAR) IS NULL OR b.category = :category)
                    AND (CAST(:benefitId AS BIGINT) IS NULL OR b.id = :benefitId)
                AND (CAST(:openAt AS TIME) IS NULL OR CASE
                            WHEN s.open_time < s.close_time
                                THEN CAST(:openAt AS TIME) >= s.open_time AND
                                     CAST(:openAt AS TIME) < s.close_time
                                ELSE CAST(:openAt AS TIME) >= s.open_time OR 
                                     CAST(:openAt AS TIME) < s.close_time 
                                END)
            ) ranked
            WHERE CAST(:radiusKm AS DOUBLE PRECISION) IS NULL OR distance_km <= :radiusKm
            ORDER BY distance_km
            LIMIT :limit
            """, nativeQuery = true)
    List<PartnerStoreDistanceProjection> findPartnersNearby(
            @Param("lat") BigDecimal lat,
            @Param("lng") BigDecimal lng,
            @Param("category") String category,
            @Param("benefitId") Long benefitId,
            @Param("radiusKm") Double radiusKm,
            @Param("openAt") LocalTime openAt,
            @Param("limit") int limit);

    // 브랜드는 연결 테이블(store_benefits)에 있어서 브랜드만 바꾸면 매장 행이 바뀌지 않아 수정일이 자동 갱신되지 않는다
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Store s SET s.updatedAt = :updatedAt WHERE s.id = :storeId")
    void touchUpdatedAt(@Param("storeId") Long storeId, @Param("updatedAt") LocalDateTime updatedAt);
}