package com.vita.search.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RetrievalMetricsTest {

	private static final Map<String, Integer> GRADES = Map.of("a", 3, "b", 2, "c", 2, "d", 1);

	@Test
	void recallIsTheShareOfRelevantFaqsFoundInThePool() {
		assertThat(RetrievalMetrics.recall(Set.of("a", "b", "c", "d"), List.of("a", "x", "c"))).isEqualTo(0.5);
		assertThat(RetrievalMetrics.recall(Set.of("a"), List.of("a", "a"))).isEqualTo(1.0);
		assertThat(RetrievalMetrics.recall(Set.of(), List.of("a"))).isZero();
	}

	@Test
	void groupRecallCountsAnAnswerGroupAsFoundWhenAnyOfItsFaqsIsRetrieved() {
		List<List<String>> groups = List.of(List.of("a", "a-P1"), List.of("b", "b-P1"), List.of("c"));
		// a 묶음은 변형(a-P1)만 가져와도 찾은 것이고, c는 못 찾았다.
		assertThat(RetrievalMetrics.groupRecall(groups, List.of("a-P1", "b", "x"))).isCloseTo(2.0 / 3, within(1e-9));
		assertThat(RetrievalMetrics.groupRecall(groups, List.of("x"))).isZero();
		assertThat(RetrievalMetrics.groupRecall(List.of(), List.of("a"))).isZero();
	}

	@Test
	void precisionCountsOnlyResultsAtOrAboveTheMinimumGradeAndKeepsKAsTheDenominator() {
		assertThat(RetrievalMetrics.precisionAtK(List.of("a", "d", "x"), GRADES, 3, 2)).isCloseTo(1.0 / 3, within(1e-9));
		assertThat(RetrievalMetrics.precisionAtK(List.of("a", "b", "c"), GRADES, 3, 2)).isEqualTo(1.0);
		// 결과가 k개보다 적어도 분모는 k다.
		assertThat(RetrievalMetrics.precisionAtK(List.of("a"), GRADES, 3, 2)).isCloseTo(1.0 / 3, within(1e-9));
	}

	@Test
	void reciprocalRankUsesTheFirstResultAtOrAboveTheMinimumGrade() {
		assertThat(RetrievalMetrics.reciprocalRank(List.of("x", "d", "b"), GRADES, 2)).isCloseTo(1.0 / 3, within(1e-9));
		assertThat(RetrievalMetrics.reciprocalRank(List.of("x", "d", "b"), GRADES, 1)).isEqualTo(0.5);
		assertThat(RetrievalMetrics.reciprocalRank(List.of("x", "y"), GRADES, 2)).isZero();
	}

	@Test
	void hitAtKIsOneWhenAnyOfTheTopKIsRelevant() {
		assertThat(RetrievalMetrics.hitAtK(List.of("x", "b"), GRADES, 1, 2)).isZero();
		assertThat(RetrievalMetrics.hitAtK(List.of("x", "b"), GRADES, 3, 2)).isEqualTo(1.0);
		assertThat(RetrievalMetrics.hitAtK(List.of("x", "d"), GRADES, 3, 2)).isZero();
	}

	@Test
	void ndcgIsOneForTheIdealOrderAndLowerForAWorseOrder() {
		List<Integer> ideal = List.of(3, 2, 2);
		assertThat(RetrievalMetrics.ndcgAtK(List.of("a", "b", "c"), GRADES, ideal, 3)).isEqualTo(1.0);

		double idcg = 3 + 2 / log2(3) + 2 / 2.0;
		double dcg = 2 + 3 / log2(3);
		assertThat(RetrievalMetrics.ndcgAtK(List.of("b", "a", "x"), GRADES, ideal, 3)).isCloseTo(dcg / idcg, within(1e-9));
		assertThat(RetrievalMetrics.ndcgAtK(List.of("x", "y", "z"), GRADES, ideal, 3)).isZero();
		assertThat(RetrievalMetrics.ndcgAtK(List.of("a"), GRADES, List.of(), 3)).isZero();
	}

	@Test
	void meanAndPrefixMeanAndStdDevMatchHandCalculation() {
		double[] values = {1.0, 0.0, 1.0, 1.0};
		assertThat(RetrievalMetrics.mean(values)).isEqualTo(0.75);
		assertThat(RetrievalMetrics.prefixMean(values, 2)).isEqualTo(0.5);
		assertThat(RetrievalMetrics.prefixMean(values, 10)).isEqualTo(0.75);
		assertThat(RetrievalMetrics.stdDev(new double[] {2.0, 4.0})).isCloseTo(Math.sqrt(2.0), within(1e-9));
		assertThat(RetrievalMetrics.stdDev(new double[] {5.0})).isZero();
	}

	@Test
	void bootstrapStdIsZeroForIdenticalValuesDeterministicAndShrinksWithMoreQuestions() {
		assertThat(RetrievalMetrics.bootstrapStdOfMean(new double[] {1, 1, 1, 1}, 10, 200, 7L)).isZero();

		double[] mixed = new double[200];
		for (int i = 0; i < mixed.length; i++) {
			mixed[i] = i % 2 == 0 ? 1.0 : 0.0;
		}
		double small = RetrievalMetrics.bootstrapStdOfMean(mixed, 25, 500, 11L);
		double again = RetrievalMetrics.bootstrapStdOfMean(mixed, 25, 500, 11L);
		double large = RetrievalMetrics.bootstrapStdOfMean(mixed, 200, 500, 11L);
		assertThat(small).isEqualTo(again);
		assertThat(large).isLessThan(small);
	}

	private static double log2(double value) {
		return Math.log(value) / Math.log(2);
	}

	@Test
	void percentileUsesTheNearestRankAndIgnoresInputOrder() {
		double[] values = {50, 10, 40, 20, 30, 60, 90, 80, 70, 100};
		assertThat(RetrievalMetrics.percentile(values, 0.95)).isEqualTo(100.0);
		assertThat(RetrievalMetrics.percentile(values, 0.5)).isEqualTo(50.0);
		assertThat(RetrievalMetrics.percentile(values, 0.1)).isEqualTo(10.0);
		assertThat(RetrievalMetrics.percentile(new double[] {}, 0.95)).isZero();
		// 입력 배열은 바뀌지 않는다.
		assertThat(values[0]).isEqualTo(50.0);
	}
}
