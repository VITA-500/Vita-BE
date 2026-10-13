package com.vita.store.repository;

import com.vita.store.entity.StoreBenefit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface StoreBenefitRepository extends JpaRepository<StoreBenefit, Long> {
    /** 여러 매장의 연결 혜택을 한 번에 조회 (매장마다 조회하면 N+1) */
    @Query("SELECT sb FROM StoreBenefit sb JOIN FETCH sb.benefit WHERE sb.store.id IN :storeIds")
    List<StoreBenefit> findWithBenefitByStoreIds(@Param("storeIds")Collection<Long> storeIds);

    /** 매장의 브랜드 연결 삭제 (브랜드를 바꿀 때 기존 연결을 지운다) */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM StoreBenefit sb WHERE sb.store.id = :storeId")
    void deleteByStoreId(@Param("storeId") Long storeId);

    /** 브랜드에 연결된 매장 수 */
    @Query("SELECT COUNT(sb) FROM StoreBenefit sb WHERE sb.benefit.id = :benefitId")
    long countByBenefitId(@Param("benefitId") Long benefitId);
}
