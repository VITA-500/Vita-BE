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

		// 형식 규칙은 PasswordPolicy가 검사한다 — 위반 시 VALIDATION_ERROR가 아니라
		// INVALID_PASSWORD_FORMAT으로 응답해야 해서 여기서는 필수 여부만 본다.
		@Schema(description = "비밀번호 (8자 이상, 영문/숫자/특수문자 포함)", example = "P@ssw0rd123")
		@NotBlank(message = "비밀번호는 필수입니다.")
		String password,

		@Schema(description = "이름", example = "홍길동")
		@NotBlank(message = "이름은 필수입니다.")
		@Size(max = 100, message = "이름은 100자를 넘을 수 없습니다.")
		String name
) {
}
