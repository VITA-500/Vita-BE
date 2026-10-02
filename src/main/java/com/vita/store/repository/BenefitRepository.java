package com.vita.store.repository;

import com.vita.store.dto.response.BenefitResponse;
import com.vita.store.entity.Benefit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface BenefitRepository extends JpaRepository<Benefit, Long> {
    @Query("""
            SELECT new com.vita.store.dto.response.BenefitResponse(
                    b.id, b.brand, b.name, b.category, b.description, COUNT(sb.id))
            FROM Benefit b
            LEFT JOIN StoreBenefit sb ON sb.benefit = b
            GROUP BY b.id, b.brand, b.name, b.category, b.description
            ORDER BY b.id
        """)
    List<BenefitResponse> findAllWithStoreCount();
}
