package com.vita.store.repository;

import com.vita.store.entity.Store;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface StoreRepository extends JpaRepository<Store, Long>  {

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
            ) ranked
            WHERE distance_km <= :radiusKm
            ORDER BY distance_km
            """, nativeQuery = true)
    List<StoreDistanceProjection> findNearBy(
            @Param("lat") BigDecimal lat, @Param("lng") BigDecimal lng, @Param("radiusKm") double radiusKm);

    @Query("""
            SELECT s FROM Store s
            WHERE s.name LIKE CONCAT('%', :keyword, '%') OR
                        s.address LIKE CONCAT('%', :keyword, '%')
            """)
    Page<Store> search(@Param("keyword") String keyword, Pageable pageable);
}
