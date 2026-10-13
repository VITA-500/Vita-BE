package com.vita.search.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 요금제 평가가 질문을 조건 매칭·벡터·정형 조회 경로로 나누는 규칙을 검증한다. */
class PlanEvalPathTest {

	@Test
	void splitsSearchQuestionsByWhetherConditionsNarrowedThePlans() {
		assertThat(PlanEvalPath.of(PlanEvalPath.SEARCH, true)).isEqualTo(PlanEvalPath.CONDITION_MATCHED);
		assertThat(PlanEvalPath.of(PlanEvalPath.SEARCH, false)).isEqualTo(PlanEvalPath.VECTOR);
	}

	@Test
	void treatsExtremeQuestionsAsLookupWhateverTheConditionFlagSays() {
		assertThat(PlanEvalPath.of(PlanEvalPath.LOOKUP_NAME, false)).isEqualTo(PlanEvalPath.LOOKUP);
		assertThat(PlanEvalPath.of(PlanEvalPath.LOOKUP_NAME, true)).isEqualTo(PlanEvalPath.LOOKUP);
	}

	@Test
	void rejectsAnUnknownPathName() {
		assertThatThrownBy(() -> PlanEvalPath.of("other", false))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("other");
	}

	@Test
	void hasAReadableLabelForEveryPath() {
		for (PlanEvalPath path : PlanEvalPath.values()) {
			assertThat(path.label()).isNotBlank();
		}
	}
}
