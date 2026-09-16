package com.vita.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 자체 회원가입 요청. role 필드가 없는 것은 의도적이다 — 권한을 요청으로 받으면
 * 누구나 {"role":"ADMIN"}을 보내 관리자가 될 수 있다. 권한은 항상 서버가 정한다.
 */
@Schema(description = "자체 회원가입 요청")
public record SignupRequest(

		@Schema(description = "로그인에 사용할 이메일", example = "hong@example.com")
		@NotBlank(message = "이메일은 필수입니다.")
		@Email(message = "이메일 형식이 아닙니다.")
		String email,

		@Schema(description = "비밀번호 (8자 이상)", example = "password1234")
		@NotBlank(message = "비밀번호는 필수입니다.")
		@Size(min = 8, message = "비밀번호는 8자 이상이어야 합니다.")
		String password,

		@Schema(description = "이름", example = "홍길동")
		@NotBlank(message = "이름은 필수입니다.")
		@Size(max = 100, message = "이름은 100자를 넘을 수 없습니다.")
		String name
) {
}
