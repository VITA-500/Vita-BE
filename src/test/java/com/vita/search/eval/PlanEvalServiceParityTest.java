package com.vita.search.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.vita.search.pipeline.TransformInfo;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 평가 러너가 서비스처럼, 변환기가 요금제용 질문을 비워 둔 질문의 요금제 결과를 버리는지 확인한다. */
class PlanEvalServiceParityTest {

	private static final List<String> CODES = List.of("VITA-LITE-5", "VITA-LITE-10");

	@Test
	void dropsPlansWhenTheTransformerLeftThePlanQueryEmpty() {
		assertThat(PlanEvalRunner.serviceVisiblePlanCodes(TransformInfo.partialNull(false, true), CODES)).isEmpty();
		assertThat(PlanEvalRunner.serviceVisiblePlanCodes(TransformInfo.partialNull(true, true), CODES)).isEmpty();
	}

	@Test
	void keepsPlansWhenThePlanQueryExistsOrTheTransformFellBackToTheOriginal() {
		assertThat(PlanEvalRunner.serviceVisiblePlanCodes(TransformInfo.partialNull(true, false), CODES)).isEqualTo(CODES);
		assertThat(PlanEvalRunner.serviceVisiblePlanCodes(TransformInfo.changed(), CODES)).isEqualTo(CODES);
		assertThat(PlanEvalRunner.serviceVisiblePlanCodes(TransformInfo.identity(), CODES)).isEqualTo(CODES);
		// 둘 다 비면 서비스도 원문으로 양쪽을 검색하므로 요금제 결과를 그대로 쓴다.
		assertThat(PlanEvalRunner.serviceVisiblePlanCodes(TransformInfo.fallback(TransformInfo.REASON_BOTH_NULL), CODES))
				.isEqualTo(CODES);
	}
}
