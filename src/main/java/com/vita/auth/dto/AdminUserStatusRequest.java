package com.vita.auth.dto;

import com.vita.auth.UserStatus;
import jakarta.validation.constraints.*;

public record AdminUserStatusRequest(@NotNull UserStatus status, @NotBlank @Size(max = 500) String reason) {}
