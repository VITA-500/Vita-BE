package com.vita.store.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 매장 방문 예약
 * 실제 가용성 확인과 점주 알림 없이 바로 저장
 */

@Entity
@Table(name = "store_reservations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreReservation {

    public enum Status { CONFIRMED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "reservation_date", nullable = false)
    private LocalDate reservationDate;

    @Column(name = "reservation_time", nullable = false)
    private LocalTime reservationTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static StoreReservation confirmed(Store store, Long userId,
                                             LocalDate date, LocalTime time){
        StoreReservation reservation = new StoreReservation();
        reservation.store = store;
        reservation.userId = userId;
        reservation.reservationDate = date;
        reservation.reservationTime = time;
        reservation.status = Status.CONFIRMED;
        reservation.createdAt = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        return reservation;
    }
}
