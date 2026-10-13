package com.vita.auth.dto;

import jakarta.validation.constraints.*;

/** null은 유지, phone의 빈 문자열은 연락처 삭제. 이메일/비밀번호는 관리자 수정 대상이 아니다. */
public record AdminUserUpdateRequest(
    @Pattern(regexp = "(?s).*\\S.*", message = "이름은 공백일 수 없습니다.") @Size(max = 100) String name,
    @Size(max = 20) String phone,
    @NotBlank @Size(max = 500) String reason
) {}
