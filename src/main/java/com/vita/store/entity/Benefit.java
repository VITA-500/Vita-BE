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

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 30)
    private String category;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Builder
    public Benefit(String name, String category, String description, Long updatedBy){
        this.name = name;
        this.category = category;
        this.description = description;
        this.updatedBy = updatedBy;
    }
}
