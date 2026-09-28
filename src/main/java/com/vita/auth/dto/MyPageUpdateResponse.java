package com.vita.auth.dto;

import com.vita.auth.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 04_API명세서 2.2 내 정보 수정 응답.
 *
 * <p>비밀번호는 바뀌었는지조차 응답에 담지 않는다. 화면에 다시 보여줄 값이 아니고,
 * 로그나 캐시에 남을 이유도 없다.
 */
@Schema(description = "내 정보 수정 결과")
public record MyPageUpdateResponse(

		@Schema(description = "사용자 ID", example = "1")
		Long userId,

		@Schema(description = "수정 후 이름", example = "김어진")
		String name,

		@Schema(description = "수정 시각 (KST)", example = "2026-09-21T11:00:00")
		LocalDateTime updatedAt
) {

	public static MyPageUpdateResponse from(User user) {
		return new MyPageUpdateResponse(user.getId(), user.getName(), user.getUpdatedAt());
	}
}
