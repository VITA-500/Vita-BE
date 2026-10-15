package com.vita.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vita.search.dto.FaqRetrievalContext;
import com.vita.search.pipeline.RetrievalOptions;
import com.vita.search.pipeline.RetrievalPipeline;
import com.vita.search.pipeline.RetrievalResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/** BE4가 부르는 진입점이 파이프라인 결과 중 응답(Context)만 그대로 돌려주는지 확인한다. 검색 동작 자체는 RetrievalPipelineTest가 검증한다. */
class FaqRetrievalServiceImplTest {

	@Test
	void returnsThePipelinesContextForTheServiceOptions() {
		RetrievalPipeline pipeline = mock(RetrievalPipeline.class);
		FaqRetrievalContext context = new FaqRetrievalContext(List.of(), List.of(), 0.42);
		RetrievalResult result = mock(RetrievalResult.class);
		when(result.context()).thenReturn(context);
		when(pipeline.run(eq("로밍 신청"), any(RetrievalOptions.class))).thenReturn(result);
		when(pipeline.run("로밍 신청", 3)).thenReturn(result);

		FaqRetrievalContext actual = new FaqRetrievalServiceImpl(pipeline).search("로밍 신청", 3);

		assertThat(actual).isSameAs(context);
	}

	@Test
	void passesTheOriginalAndBothPresetQueriesToThePipelineWithoutRunningTheTransformer() {
		RetrievalPipeline pipeline = mock(RetrievalPipeline.class);
		FaqRetrievalContext context = new FaqRetrievalContext(List.of(), List.of(), 0.5);
		RetrievalResult result = mock(RetrievalResult.class);
		when(result.context()).thenReturn(context);
		when(pipeline.run(eq("원문"), eq("FAQ용"), eq("요금제용"), any(RetrievalOptions.class))).thenReturn(result);

		FaqRetrievalContext actual = new FaqRetrievalServiceImpl(pipeline).search("원문", "FAQ용", "요금제용", 10);

		assertThat(actual).isSameAs(context);
	}
}
