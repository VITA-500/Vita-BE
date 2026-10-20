package com.vita.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vita.chat.client.BedrockChatClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 변환 응답의 faq_sub_queries(의도별 FAQ 질문) 파싱 규칙을 고정한다. */
class LlmQueryTransformerSubQueryTest {

	private LlmQueryTransformer.TransformOutcome transform(String json) {
		BedrockChatClient client = mock(BedrockChatClient.class);
		when(client.complete(anyString(), anyString())).thenReturn(json);
		LlmQueryTransformer transformer = new LlmQueryTransformer(client);
		ReflectionTestUtils.setField(transformer, "enabled", true);
		return transformer.transformWithStatus("질문", null);
	}

	@Test
	void readsTheSubQueriesWhenThereAreAtLeastTwo() {
		var outcome = transform("{\"faq_query\": \"전체\", \"plan_query\": null, \"faq_sub_queries\": [\"하나\", \"둘\"]}");

		assertThat(outcome.result().faqSubQueries()).containsExactly("하나", "둘");
	}

	@Test
	void treatsASingleSubQueryAsNoDecomposition() {
		var outcome = transform("{\"faq_query\": \"전체\", \"plan_query\": null, \"faq_sub_queries\": [\"하나\"]}");

		assertThat(outcome.result().faqSubQueries()).isEmpty();
	}

	@Test
	void dropsBlankDuplicateAndMainQueryEntriesAndKeepsAtMostThree() {
		var outcome = transform("{\"faq_query\": \"전체\", \"plan_query\": null, \"faq_sub_queries\": "
				+ "[\"하나\", \"\", \"하나\", \"전체\", \"둘\", \"셋\", \"넷\"]}");

		assertThat(outcome.result().faqSubQueries()).containsExactly("하나", "둘", "셋");
	}

	@Test
	void ignoresSubQueriesWhenThereIsNoFaqQuery() {
		var outcome = transform("{\"faq_query\": null, \"plan_query\": \"플랜\", \"faq_sub_queries\": [\"하나\", \"둘\"]}");

		assertThat(outcome.result().faqSubQueries()).isEmpty();
	}

	@Test
	void defaultsToNoSubQueriesWhenTheFieldIsMissing() {
		var outcome = transform("{\"faq_query\": \"전체\", \"plan_query\": null}");

		assertThat(outcome.result().faqSubQueries()).isEmpty();
	}
}
