package com.vita.store.entity;

import com.vita.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "benefits")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Benefit extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 1브랜드 = 1혜택. 제휴 매장 이름 앞부분과 같아서 매장 연결 기준으로도 쓴다
    @Column(nullable = false, length = 50, unique = true)
    private String brand;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 30)
    private String category;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Builder
    public Benefit(String brand, String name, String category, String description, Long updatedBy){
        this.brand = brand;
        this.name = name;
        this.category = category;
        this.description = description;
        this.updatedBy = updatedBy;
    }
}
