package com.vita.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 기록용 JSON이 정상일 때는 그대로, 너무 크거나 바꿀 수 없을 때는 예외 없이 작은 표시 JSON으로 대신되는지 확인한다. */
class TraceJsonTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void keepsASmallValueAsIs() throws Exception {
		String json = TraceJson.toJson(mapper, Map.of("question", "로밍 데이터 얼마예요?"), 1000);

		assertThat(mapper.readTree(json).get("question").asText()).isEqualTo("로밍 데이터 얼마예요?");
	}

	@Test
	void replacesATooLargeValueWithATruncatedMarkerAndPreview() throws Exception {
		String big = "가".repeat(5000);

		String json = TraceJson.toJson(mapper, Map.of("answer", big), 1000);
		JsonNode node = mapper.readTree(json);

		assertThat(node.get("truncated").asBoolean()).isTrue();
		assertThat(node.get("original_chars").asInt()).isGreaterThan(1000);
		assertThat(node.get("preview").asText()).hasSize(TraceJson.PREVIEW_CHARS);
		assertThat(json.length()).isLessThan(big.length());
	}

	@Test
	void turnsAValueThatCannotBeSerializedIntoAMarkerInsteadOfThrowing() throws Exception {
		Map<String, Object> selfReference = new HashMap<>();
		selfReference.put("self", selfReference);

		String json = TraceJson.toJson(mapper, selfReference, 1000);

		assertThat(mapper.readTree(json).has("serialization_error")).isTrue();
	}
}
