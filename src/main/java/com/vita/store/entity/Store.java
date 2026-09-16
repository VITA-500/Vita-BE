package com.vita.store.entity;

import com.vita.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "store")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Store extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 255)
    private String address;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal lat;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal lng;

    @Column(name = "business_hours", length = 100)
    private String businessHours;

    @Column(length = 20)
    private String phone;

    @Builder
    public Store(String name, String address, BigDecimal lat, BigDecimal lng, String businessHours, String phone){
        this.name = name;
        this.address = address;
        this.lat = lat;
        this.lng = lng;
        this.businessHours = businessHours;
        this.phone = phone;
    }

    public void update(String name, String address, BigDecimal lat, BigDecimal lng, String businessHours, String phone){
        this.name = name;
        this.address = address;
        this.lat = lat;
        this.lng = lng;
        this.businessHours = businessHours;
        this.phone = phone;
    }
}
