package com.vita.auth.dto;

import com.vita.auth.Role;
import jakarta.validation.constraints.*;

public record AdminUserRoleRequest(@NotNull Role role, @NotBlank @Size(max = 500) String reason) {}
