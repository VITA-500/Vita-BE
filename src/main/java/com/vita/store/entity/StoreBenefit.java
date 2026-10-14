package com.vita.store.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 제휴 매장과 브랜드(혜택) 연결. 매장 하나에 브랜드 하나 */
@Entity
@Table(name = "store_benefits",
        uniqueConstraints = @UniqueConstraint(name = "uk_store_benefit",
        columnNames = {"store_id", "benefit_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreBenefit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "benefit_id", nullable = false)
    private Benefit benefit;

    public StoreBenefit(Store store, Benefit benefit){
        this.store = store;
        this.benefit = benefit;
    }
}
