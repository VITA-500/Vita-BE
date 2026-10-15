package com.vita.search.pipeline;

import com.vita.search.dto.FaqSimilarityResult;
import java.util.List;

/**
 * 후보 풀에서 최종 FAQ 결과를 고른 과정을 단계별로 담은 것. 후처리는 분류 이름 가산점 재정렬 → 같은 답변 중복 제거 → topK →
 * threshold 순서다. 평가 러너가 각 단계 결과를 지표에 쓰고, BE4 Trace에도 그대로 남길 수 있다.
 *
 * @param pool          후처리에 들어온 후보 풀(검색기가 준 순위 그대로)
 * @param ranked        분류 이름 가산점으로 순위를 다시 매긴 풀(유사도 값은 그대로)
 * @param candidates    같은 답변을 하나로 합치고 topK개로 자른 후보(threshold 적용 전)
 * @param results       candidates 중 유사도가 FAQ threshold 이상인 결과. BE4에 전달되는 참고 FAQ다.
 * @param topSimilarity 풀 전체에서 가장 높은 원래 유사도. 재정렬로 1등이 바뀌어도 값은 달라지지 않고,
 *                      threshold 미달이어도 BE4에 알려야 해서 결과와 별개로 둔다. 풀이 비면 0.0.
 */
public record FaqSelection(
		List<FaqSimilarityResult> pool,
		List<FaqSimilarityResult> ranked,
		List<FaqSimilarityResult> candidates,
		List<FaqSimilarityResult> results,
		double topSimilarity) {
}
