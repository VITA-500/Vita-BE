package com.vita.auth.dto;

import com.vita.auth.AuthProvider;
import com.vita.auth.entity.User;
import com.vita.common.util.MaskingUtil;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 04_API명세서 2.1 내 정보 응답.
 *
 * <p>엔티티를 그대로 반환하지 않는 이유 — User에는 passwordHash가 있고, 엔티티를 직렬화하면
 * 그 값이 그대로 응답에 실려 나간다.
 *
 * <p>hasPassword / linkedProviders는 명세에 없지만 FE가 화면을 분기하는 데 필요해 추가했다
 * (소셜 전용 계정은 비밀번호 변경 UI를 숨겨야 한다). 명세서에 반영 필요.
 *
 * <p>phone은 명세(2.1)에 있었지만 뺐다 — 회원가입·수정·소셜 어디에도 입력 경로가 없어 항상
 * null로 나갔다. 전화번호를 쓰는 화면이 생기면 입력 경로와 함께 되살린다(users.phone 컬럼은 남아 있다).
 */
@Schema(description = "내 정보")
public record MyPageResponse(

		@Schema(description = "사용자 ID", example = "1")
		Long userId,

		@Schema(description = "마스킹된 이메일. 소셜 가입자는 null일 수 있다", example = "us**@example.com")
		String email,

		@Schema(description = "이름", example = "김어진")
		String name,

		@Schema(description = "권한", example = "USER")
		String role,

		@Schema(description = "비밀번호 로그인 가능 여부. false면 비밀번호 변경 UI를 숨긴다", example = "true")
		boolean hasPassword,

		@Schema(description = "연결된 소셜 계정. 없으면 빈 배열", example = "[\"KAKAO\"]")
		List<String> linkedProviders
) {

	public static MyPageResponse of(User user, List<AuthProvider> providers) {
		return new MyPageResponse(
				user.getId(),
				MaskingUtil.email(user.getEmail()),
				user.getName(),
				user.getRole().name(),
				user.hasPassword(),
				providers.stream().map(AuthProvider::name).toList()
		);
	}
}
