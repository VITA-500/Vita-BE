package com.vita.store.repository;

import com.vita.store.entity.StoreBenefit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface StoreBenefitRepository extends JpaRepository<StoreBenefit, Long> {
    // 목록 한 페이지의 매장 혜택을 한 번에 가져옴
    @Query("SELECT sb FROM StoreBenefit sb JOIN FETCH sb.benefit WHERE sb.store.id IN :storeIds")
    List<StoreBenefit> findWithBenefitByStoreIds(@Param("storeIds")Collection<Long> storeIds);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM StoreBenefit sb WHERE sb.store.id = :storeId")
    void deleteByStoreId(@Param("storeId") Long storeId);

    @Query("SELECT COUNT(sb) FROM StoreBenefit sb WHERE sb.benefit.id = :benefitId")
    long countByBenefitId(@Param("benefitId") Long benefitId);
}
