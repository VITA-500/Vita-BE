package com.vita.search.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanEvalMetricsTest {

	/** 정답이 A(3점), B(3점), C(2점), D(1점, 기기 전용)인 일반 질문. */
	private static PlanEvalQuestion normal() {
		return new PlanEvalQuestion("P900", "질문", "CASUAL", "PRICE_RANGE", null, false, false, false, false,
				Map.of("3", List.of("A", "B"), "2", List.of("C"), "1", List.of("D")), List.of(), "", "", 1);
	}

	private static PlanEvalQuestion extreme() {
		return new PlanEvalQuestion("P901", "가장 저렴한 요금제", "CASUAL", "EXTREME", "CHEAPEST", false, false, false, false,
				Map.of("3", List.of("LITE"), "2", List.of("KIDS"), "1", List.of("WATCH")), List.of(), "", "", 2);
	}

	@Test
	void requiredCodesAreTheStrongAnswersOrTheBestAnswerForSuperlatives() {
		assertThat(PlanEvalMetrics.requiredCodes(normal())).containsExactlyInAnyOrder("A", "B", "C");
		assertThat(PlanEvalMetrics.requiredCodes(extreme())).containsExactly("LITE");
		PlanEvalQuestion deviceOnly = new PlanEvalQuestion("P902", "1만1천원짜리", "CASUAL", "PRICE_EXACT", null, false, false,
				false, false, Map.of("1", List.of("WATCH")), List.of(), "", "", 3);
		// 2점 이상 정답이 없으면 1점 이상을 꼭 나와야 하는 요금제로 본다.
		assertThat(PlanEvalMetrics.requiredCodes(deviceOnly)).containsExactly("WATCH");
	}

	@Test
	void recallCountsHowMuchOfTheAnswerSetWasDelivered() {
		PlanEvalMetrics.Score score = PlanEvalMetrics.score(normal(), List.of("A", "C"));

		assertThat(score.recallAll()).isEqualTo(0.5); // 정답 A,B,C,D 중 A,C
		assertThat(score.recallStrong()).isCloseTo(2.0 / 3, within(1e-9)); // 2점 이상 A,B,C 중 A,C
		assertThat(score.precision()).isEqualTo(1.0);
		assertThat(score.hit1()).isEqualTo(1.0);
		assertThat(score.mrr()).isEqualTo(1.0);
		assertThat(score.delivered()).isEqualTo(2);
	}

	@Test
	void exactMatchNeedsEveryRequiredPlanAndNoWrongPlan() {
		assertThat(PlanEvalMetrics.score(normal(), List.of("A", "C")).exact()).isZero(); // B가 빠짐
		assertThat(PlanEvalMetrics.score(normal(), List.of("A", "B", "C")).exact()).isEqualTo(1.0);
		// 기기 전용(1점)은 나와도 틀린 것이 아니다.
		assertThat(PlanEvalMetrics.score(normal(), List.of("A", "B", "C", "D")).exact()).isEqualTo(1.0);
		// 정답지에 없는 요금제가 섞이면 틀린 것이다.
		assertThat(PlanEvalMetrics.score(normal(), List.of("A", "B", "C", "X")).exact()).isZero();
	}

	@Test
	void precisionIsNotAvailableWhenNothingWasDelivered() {
		PlanEvalMetrics.Score score = PlanEvalMetrics.score(normal(), List.of());

		assertThat(score.precision()).isNaN();
		assertThat(score.recallAll()).isZero();
		assertThat(score.hit1()).isZero();
		assertThat(score.mrr()).isZero();
	}

	@Test
	void recallStrongIsNotAvailableWhenOnlyDeviceOnlyPlansAreAnswers() {
		PlanEvalQuestion deviceOnly = new PlanEvalQuestion("P902", "1만1천원짜리", "CASUAL", "PRICE_EXACT", null, false, false,
				false, false, Map.of("1", List.of("WATCH")), List.of(), "", "", 3);

		PlanEvalMetrics.Score score = PlanEvalMetrics.score(deviceOnly, List.of("WATCH"));

		assertThat(score.recallStrong()).isNaN();
		assertThat(score.recallAll()).isEqualTo(1.0);
		assertThat(score.exact()).isEqualTo(1.0);
	}

	@Test
	void mrrUsesTheFirstRequiredPlanRank() {
		PlanEvalMetrics.Score score = PlanEvalMetrics.score(normal(), List.of("X", "D", "B"));

		assertThat(score.mrr()).isCloseTo(1.0 / 3, within(1e-9));
		assertThat(score.hit1()).isZero();
	}

	@Test
	void ndcgRewardsHigherGradesAtHigherRanks() {
		PlanEvalQuestion question = new PlanEvalQuestion("P903", "질문", "CASUAL", "NAME", null, false, false, false, false,
				Map.of("3", List.of("A"), "2", List.of("B")), List.of(), "", "", 4);

		// DCG = 3 / log2(3) = 1.893, 이상적인 DCG = 3 + 2 / log2(3) = 4.262
		assertThat(PlanEvalMetrics.score(question, List.of("X", "A")).ndcg()).isCloseTo(0.444, within(0.001));
		assertThat(PlanEvalMetrics.score(question, List.of("A", "B")).ndcg()).isEqualTo(1.0);
	}

	@Test
	void superlativeQuestionsOnlyCountTheBestAnswerAsCorrect() {
		PlanEvalMetrics.Score best = PlanEvalMetrics.score(extreme(), List.of("LITE"));
		PlanEvalMetrics.Score alternative = PlanEvalMetrics.score(extreme(), List.of("KIDS"));

		assertThat(best.hit1()).isEqualTo(1.0);
		assertThat(best.ndcg()).isEqualTo(1.0); // 이상적인 순서에 2점·1점 대안을 넣지 않는다
		assertThat(alternative.hit1()).isZero(); // 더 싼 연령 제한 요금제는 "가장 저렴한" 답이 아니다
		assertThat(alternative.precision()).isEqualTo(1.0); // 틀린 것은 아니다
	}

	@Test
	void leakAndAlternativeChecksForQuestionsWithoutAnExactAnswer() {
		PlanEvalQuestion noExact = new PlanEvalQuestion("P904", "청년 요금제 중에 데이터 무제한 있어?", "CASUAL", "NO_EXACT", null,
				false, true, false, false, Map.of(), List.of("YOUTH-70", "MAX"), "", "", 5);

		assertThat(PlanEvalMetrics.leaked(List.of())).isFalse();
		assertThat(PlanEvalMetrics.leaked(List.of("MAX"))).isTrue();
		assertThat(PlanEvalMetrics.alternativeDelivered(noExact, List.of("LITE-5", "MAX"))).isTrue();
		assertThat(PlanEvalMetrics.alternativeDelivered(noExact, List.of("LITE-5"))).isFalse();
		assertThat(PlanEvalMetrics.alternativeDelivered(noExact, List.of())).isFalse();
	}
}
