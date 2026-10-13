package com.vita.store.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.time.LocalTime;

public record ReservationResponse(
        Long reservationId,
        Long storeId,
        String storeName,               // 예약 완료 안내 문구용
        LocalDate date,
        @JsonFormat(pattern = "HH:mm") LocalTime time,
        String status) { }              // CONFIRMED