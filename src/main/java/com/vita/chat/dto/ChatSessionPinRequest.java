package com.vita.chat.dto;

import jakarta.validation.constraints.NotNull;

public record ChatSessionPinRequest(
        @NotNull Boolean pinned) { }
