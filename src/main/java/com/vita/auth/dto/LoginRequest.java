package com.vita.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "자체 로그인 요청")
public record LoginRequest(

		@Schema(description = "가입한 이메일", example = "hong@example.com")
		@NotBlank(message = "이메일은 필수입니다.")
		String email,

		@Schema(description = "비밀번호", example = "password1234")
		@NotBlank(message = "비밀번호는 필수입니다.")
		String password
) {
}
