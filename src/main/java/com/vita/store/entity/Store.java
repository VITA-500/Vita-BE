package com.vita.store.entity;

import com.vita.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "stores")
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

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "consult_services", columnDefinition = "text[]", nullable = false)
    private List<String> consultServices = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "provided_services", columnDefinition = "text[]", nullable = false)
    private List<String> providedServices = new ArrayList<>();

    @Builder
    public Store(String name, String address, BigDecimal lat, BigDecimal lng, String businessHours,
                 String phone, List<String> consultServices, List<String> providedServices){
        this.name = name;
        this.address = address;
        this.lat = lat;
        this.lng = lng;
        this.businessHours = businessHours;
        this.phone = phone;
        this.consultServices = consultServices != null ? new ArrayList<>(consultServices) : new ArrayList<>();
        this.providedServices = providedServices != null ? new ArrayList<>(providedServices) : new ArrayList<>();
    }

    public void update(String name, String address, BigDecimal lat, BigDecimal lng, String businessHours,
                       String phone, List<String> consultServices, List<String> providedServices){
        this.name = name;
        this.address = address;
        this.lat = lat;
        this.lng = lng;
        this.businessHours = businessHours;
        this.phone = phone;
        if(consultServices != null){
            this.consultServices = new ArrayList<>(consultServices);
        }
        if(providedServices != null){
            this.providedServices = new ArrayList<>(providedServices);
        }
    }
}
