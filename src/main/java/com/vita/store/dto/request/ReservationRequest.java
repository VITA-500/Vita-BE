package com.vita.store.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;

/** 방문 예약 요청. time은 "HH:mm" */
public record ReservationRequest(
        @NotNull LocalDate date,
        @NotNull @JsonFormat(pattern = "HH:mm")LocalTime time) { }
