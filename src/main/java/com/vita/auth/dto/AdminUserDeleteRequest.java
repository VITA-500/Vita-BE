package com.vita.auth.dto;

import jakarta.validation.constraints.*;

public record AdminUserDeleteRequest(@NotBlank @Size(max = 500) String reason) {}
