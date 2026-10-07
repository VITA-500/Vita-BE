package com.vita.search.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TransformInfoTest {

	@Test
	void transformedQueryWithoutInfoIsIdentity() {
		TransformedQuery query = new TransformedQuery("원문", "faq", "plan");

		assertThat(query.info()).isEqualTo(TransformInfo.identity());
		assertThat(TransformedQuery.unchanged("원문").info().kind()).isEqualTo(TransformInfo.Kind.IDENTITY);
		assertThat(new TransformedQuery("원문", "faq", "plan", null).info()).isEqualTo(TransformInfo.identity());
	}

	@Test
	void onlyChangedAndPartialCountAsApplied() {
		assertThat(TransformInfo.identity().applied()).isFalse();
		assertThat(TransformInfo.changed().applied()).isTrue();
		assertThat(TransformInfo.partialNull(true, false).applied()).isTrue();
		assertThat(TransformInfo.fallback(TransformInfo.REASON_NO_JSON).applied()).isFalse();
	}
}
