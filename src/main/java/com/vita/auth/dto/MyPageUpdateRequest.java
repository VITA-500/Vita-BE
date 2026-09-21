package com.vita.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 04_API명세서 2.2 내 정보 수정 요청.
 *
 * <p>PATCH라 보낸 필드만 반영한다 — 둘 다 선택이고, null이면 그 항목은 건드리지 않는다.
 * 빈 문자열은 "지우겠다"가 아니라 잘못된 입력으로 보고 막는다(이름 없는 계정이 생기면 곤란하다).
 *
 * <p>email·phone·role이 없는 것은 의도적이다. email은 로그인 식별자라 바꾸면 계정이 달라지는
 * 것과 같고, role은 요청으로 받으면 누구나 관리자가 될 수 있다(SignupRequest와 같은 이유).
 * 조회 응답의 email은 마스킹된 값이라 그대로 돌려받아 저장하면 계정이 망가지기도 한다.
 */
@Schema(description = "내 정보 수정 요청 (보낸 필드만 수정된다)")
public record MyPageUpdateRequest(

		@Schema(description = "변경할 이름. 생략하면 그대로 둔다", example = "김어진")
		@Size(min = 1, max = 100, message = "이름은 1자 이상 100자 이하여야 합니다.")
		String name,

		@Schema(description = "변경할 비밀번호 (8자 이상). 생략하면 그대로 둔다", example = "newPassword1234")
		@Size(min = 8, message = "비밀번호는 8자 이상이어야 합니다.")
		String password
) {
}
