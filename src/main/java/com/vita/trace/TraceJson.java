package com.vita.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * history·config를 DB의 jsonb 칸에 넣을 JSON 문자열로 바꾼다. 기록 때문에 답변이 막히면 안 되므로, 너무 크거나 바꿀 수 없는 값도
 * 예외 없이 "잘렸음/직렬화 실패" 표시가 든 작은 JSON으로 대신한다.
 */
final class TraceJson {

	/** 잘릴 때 앞부분을 남기는 길이(글자). 무엇이 들어 있었는지 짐작할 수 있을 만큼만 둔다. */
	static final int PREVIEW_CHARS = 2000;

	private TraceJson() {
	}

	/**
	 * @param mapper   JSON 변환기
	 * @param value    JSON으로 바꿀 값
	 * @param maxChars 허용하는 최대 글자 수. 넘으면 {@code truncated} 표시와 앞부분 미리보기만 남긴다
	 */
	static String toJson(ObjectMapper mapper, Object value, int maxChars) {
		try {
			String json = mapper.writeValueAsString(value);
			if (json.length() <= maxChars) {
				return json;
			}
			Map<String, Object> truncated = new LinkedHashMap<>();
			truncated.put("truncated", true);
			truncated.put("original_chars", json.length());
			truncated.put("preview", json.substring(0, Math.min(PREVIEW_CHARS, json.length())));
			return mapper.writeValueAsString(truncated);
		} catch (JsonProcessingException | RuntimeException e) {
			try {
				return mapper.writeValueAsString(Map.of("serialization_error", String.valueOf(e.getClass().getSimpleName())));
			} catch (JsonProcessingException ignored) {
				return "{\"serialization_error\":\"unknown\"}";
			}
		}
	}
}
