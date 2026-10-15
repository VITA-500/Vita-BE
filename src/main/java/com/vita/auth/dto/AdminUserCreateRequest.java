package com.vita.auth.dto;

import jakarta.validation.constraints.*;

/** 관리자 생성도 일반 계정으로 시작한다. 승격은 별도 API로 기록한다. */
public record AdminUserCreateRequest(
    @NotBlank @Email @Size(max = 255) String email,
    @NotBlank String password,
    @NotBlank @Size(max = 100) String name,
    @Size(max = 20) String phone,
    @NotBlank @Size(max = 500) String reason
) {}
