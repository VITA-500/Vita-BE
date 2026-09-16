package com.vita.store.repository;

import com.vita.store.entity.Store;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

public interface StoreRepository extends JpaRepository<Store, Long>  {

    @Query(value = """
            SELECT s.id, s.name, s.address, s.lat, s.lng, s.business_hours,
                s.phone, 2 * 6371 * asin(sqrt(
                        power(sin(radians(s.lat - :lat) / 2), 2)
                        + cos(radians(:lat)) * cos(radians(s.lat)) *
                        power(sin(radians(s.lng - :lng) / 2), 2)
                        )) AS distance_km
            FROM store s
            ORDER BY distance_km
            LIMIT 1
            """, nativeQuery = true)
    Optional<StoreDistanceProjection> findNearest(@Param("lat")BigDecimal lat, @Param("lng") BigDecimal lng);
}
