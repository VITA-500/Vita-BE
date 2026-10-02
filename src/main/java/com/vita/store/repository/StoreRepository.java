package com.vita.store.repository;

import com.vita.store.entity.Store;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

// 관리자 목록 검색은 StoreSpecs 조건 조합으로 한다 (JpaSpecificationExecutor)
public interface StoreRepository extends JpaRepository<Store, Long>, JpaSpecificationExecutor<Store> {

    @Query(value = """
            SELECT s.id, s.name, s.address, s.lat, s.lng, s.business_hours,
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
                SELECT s.id, s.name, s.address, s.lat, s.lng, s.business_hours,
                s.phone, array_to_string(s.consult_services, '||') AS consult_services,
                array_to_string(s.provided_services, '||') AS provided_services,
                2 * 6371 * asin(sqrt(
                    power(sin(radians(s.lat - :lat) / 2), 2)
                    + cos(radians(:lat)) * cos(radians(s.lat)) *
                    power(sin(radians(s.lng - :lng) / 2), 2)
                    )) AS distance_km
                FROM stores s
                WHERE s.store_type = 'PHONE'
            ) ranked
            WHERE distance_km <= :radiusKm
            ORDER BY distance_km
            """, nativeQuery = true)

    List<StoreDistanceProjection> findNearBy(
            @Param("lat") BigDecimal lat, @Param("lng") BigDecimal lng, @Param("radiusKm") double radiusKm);

    // 업종별 제휴 매장 거리순, 업종 및 브랜드는 연결을 따라 조인한다.
    @Query(value = """
            SELECT *
            FROM(
                SELECT s.id, s.name, s.address, s.lat, s.lng,
                   b.id AS benefit_id, b.brand, b.category, b.name AS benefit_name,
                   2 * 6371 * asin(sqrt(
                       power(sin(radians(s.lat - :lat) / 2), 2)
                       + cos(radians(:lat)) * cos(radians(s.lat)) *
                       power(sin(radians(s.lng - :lng) / 2), 2)
                       )) AS distance_km
                FROM stores s
                JOIN store_benefits sb ON sb.store_id = s.id
                JOIN benefits b ON b.id = sb.benefit_id
                WHERE s.store_type = 'PARTNER'
                    AND b.category = :category
                    AND (CAST(:benefitId AS BIGINT) IS NULL OR b.id = :benefitId)
            ) ranked
            WHERE CAST(:radiusKm AS DOUBLE PRECISION) IS NULL OR distance_km <= :radiusKm
            ORDER BY distance_km
            LIMIT :limit
            """, nativeQuery = true)
    List<PartnerStoreDistanceProjection> findPartnersByCategory(
            @Param("lat") BigDecimal lat,
            @Param("lng") BigDecimal lng,
            @Param("category") String category,
            @Param("benefitId") Long benefitId,
            @Param("radiusKm") Double radiusKm,
            @Param("limit") int limit);

    // 브랜드는 연결 테이블(store_benefits)에 있어서 브랜드만 바꾸면 매장 행이 바뀌지 않아 수정일이 자동 갱신되지 않는다
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Store s SET s.updatedAt = :updatedAt WHERE s.id = :storeId")
    void touchUpdatedAt(@Param("storeId") Long storeId, @Param("updatedAt") LocalDateTime updatedAt);
}