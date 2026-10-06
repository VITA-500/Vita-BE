package com.vita.store.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;

public record ReservationRequest(
        @NotNull LocalDate date,
        @NotNull @JsonFormat(pattern = "HH:mm")LocalTime time) { }
