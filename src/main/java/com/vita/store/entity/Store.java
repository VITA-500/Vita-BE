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
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 매장
 * 통신 매장(PHONE)과 제휴 매장(PARTNER)을 한 테이블에 저장한다
 */
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

    // 영업시간은 두 칸으로 저장하고 응답할 때 "HH:mm-HH:mm"으로 합친다 (getBusinessHours)
    @Column(name = "open_time")
    private LocalTime openTime;

    @Column(name = "close_time")
    private LocalTime closeTime;

    @Column(length = 20)
    private String phone;

    // 상담 서비스 (예: 로밍상담, 요금제변경). 제휴 매장은 비어 있음
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "consult_services", columnDefinition = "text[]", nullable = false)
    private List<String> consultServices = new ArrayList<>();

    // 매장 편의 (예: 주차 가능, 방문예약 가능). 제휴 매장은 비어 있음
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "provided_services", columnDefinition = "text[]", nullable = false)
    private List<String> providedServices = new ArrayList<>();

    // 제휴 매장의 브랜드는 store_benefits로 연결한다
    @Enumerated(EnumType.STRING)
    @Column(name = "store_type", nullable = false, length = 20)
    private StoreType storeType = StoreType.PHONE;

    @Builder
    public Store(String name, String address, BigDecimal lat, BigDecimal lng, BusinessHours businessHours,
                 String phone, List<String> consultServices, List<String> providedServices, StoreType storeType){
        this.name = name;
        this.address = address;
        this.lat = lat;
        this.lng = lng;
        changeBusinessHours(businessHours);
        this.phone = phone;
        this.consultServices = consultServices != null ? new ArrayList<>(consultServices) : new ArrayList<>();
        this.providedServices = providedServices != null ? new ArrayList<>(providedServices) : new ArrayList<>();
        this.storeType = storeType != null ? storeType : StoreType.PHONE;
    }

    /** 매장 유형은 바꾸지 않는다. 서비스 목록은 null이면 그대로 둔다 */
    public void update(String name, String address, BigDecimal lat, BigDecimal lng, BusinessHours businessHours,
                       String phone, List<String> consultServices, List<String> providedServices){
        this.name = name;
        this.address = address;
        this.lat = lat;
        this.lng = lng;
        changeBusinessHours(businessHours);
        this.phone = phone;
        if(consultServices != null){
            this.consultServices = new ArrayList<>(consultServices);
        }
        if(providedServices != null){
            this.providedServices = new ArrayList<>(providedServices);
        }
    }

    /** 응답용 "HH:mm-HH:mm" */
    public String getBusinessHours(){
        return BusinessHours.format(openTime, closeTime);
    }

    /** 영업시간이 없으면 null */
    public BusinessHours toBusinessHours(){
        return openTime != null && closeTime != null ? new BusinessHours(openTime, closeTime) : null;
    }

    private void changeBusinessHours(BusinessHours businessHours){
        this.openTime = businessHours != null ? businessHours.open() : null;
        this.closeTime = businessHours != null ? businessHours.close() : null;
    }
}
