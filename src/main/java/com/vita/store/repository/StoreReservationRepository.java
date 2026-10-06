package com.vita.store.repository;

import com.vita.store.entity.StoreReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreReservationRepository extends JpaRepository<StoreReservation, Long> {

    // store_reservations의 매장 FK에 CASCADE가 없어서 매장을 지우기 전에 예약 먼저 지운다.
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM StoreReservation r WHERE r.store.id = :storeId")
    void deleteByStoreId(@Param("storeId") Long storeId);

}
