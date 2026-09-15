package com.vita.auth.dto;

import com.vita.auth.entity.AuthProvider;
import com.vita.auth.entity.User;
import com.vita.common.util.MaskingUtil;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 내 정보 응답. 엔티티를 그대로 반환하지 않는 이유 — User에는 passwordHash가 있고,
 * 엔티티를 직렬화하면 그 값이 그대로 응답에 실려 나간다.
 */
@Schema(description = "내 정보")
public record MyPageResponse(

		@Schema(description = "사용자 ID", example = "1")
		Long id,

		@Schema(description = "마스킹된 이메일. 카카오 가입자는 null일 수 있다", example = "ho**@example.com")
		String email,

		@Schema(description = "이름", example = "홍길동")
		String name,

		@Schema(description = "마스킹된 전화번호. 미입력이면 null", example = "010-****-5678")
		String phone,

		@Schema(description = "권한", example = "USER")
		String role,

		@Schema(description = "비밀번호 로그인 가능 여부. false면 프론트에서 비밀번호 변경 UI를 숨긴다",
				example = "true")
		boolean hasPassword,

		@Schema(description = "연결된 소셜 계정 목록. 없으면 빈 배열", example = "[\"KAKAO\"]")
		List<String> linkedProviders
) {

	public static MyPageResponse of(User user, List<AuthProvider> providers) {
		return new MyPageResponse(
				user.getId(),
				MaskingUtil.email(user.getEmail()),
				user.getName(),
				MaskingUtil.phone(user.getPhone()),
				user.getRole().name(),
				user.hasPassword(),
				providers.stream().map(AuthProvider::name).toList()
		);
	}
}
