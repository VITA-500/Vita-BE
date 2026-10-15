package com.vita.search.service;

import com.vita.search.dto.FaqSimilarityResult;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 유사도 검색 후보에서 답변이 같은 중복 항목을 걷어내고 상위 topK개를 고르는 도우미.
 *
 * <p>같은 질문을 표현만 바꿔 여러 건으로 늘린 FAQ 데이터에서는 유사도 상위 topK개가 전부 같은
 * 답변의 변형으로 채워질 수 있다. 그러면 LLM은 근거를 사실상 1개만 받게 되므로, 후보를 넉넉히
 * 가져온 뒤 중복을 제거하고 나서 topK개를 자른다.
 */
public final class FaqCandidateSelector {

	/**
	 * 후보 풀의 기본 크기. 상위 개수(topK)를 3에서 10으로 늘려도 풀은 30개로 유지하기로 정했다(2차 멘토링).
	 * 현재 데이터에서 풀 30개 안의 서로 다른 답변은 질문당 평균 18.9개(최소 15개)라 상위 10개를 채울 수 있다.
	 */
	public static final int DEFAULT_POOL_SIZE = 30;

	/** topK가 커져도 중복을 걷어낸 뒤 topK개를 채울 수 있도록, 풀은 최소 topK의 이 배수 이상으로 잡는다. */
	public static final int MIN_POOL_MULTIPLIER = 3;

	private FaqCandidateSelector() {
	}

	/**
	 * topK개의 서로 다른 답변을 얻기 위해 DB에서 가져와야 할 후보 개수. 기본은 {@link #DEFAULT_POOL_SIZE}(30)로 고정하고,
	 * topK가 아주 커서 30개로는 부족할 때만 topK의 {@link #MIN_POOL_MULTIPLIER}배로 늘린다.
	 */
	public static int poolSize(int topK) {
		return Math.max(DEFAULT_POOL_SIZE, topK * MIN_POOL_MULTIPLIER);
	}

	/**
	 * 유사도 내림차순으로 정렬된 후보에서 (category, subcategory, answer)가 모두 같은 항목은 가장
	 * 유사한 하나만 남기고, 그중 상위 topK개를 유사도 순서 그대로 반환한다. 세부분류가 다르면
	 * 답변 문장이 같아도 서로 다른 FAQ로 취급해 둘 다 남긴다.
	 *
	 * @param candidates 유사도 내림차순으로 정렬된 후보 목록
	 * @param topK       최대 반환 개수
	 */
	public static List<FaqSimilarityResult> selectDistinct(List<FaqSimilarityResult> candidates, int topK) {
		Map<List<String>, FaqSimilarityResult> distinct = new LinkedHashMap<>();
		for (FaqSimilarityResult candidate : candidates) {
			// subcategory는 선택 입력이라 null일 수 있어서, null을 허용하는 Arrays.asList로 키를 만든다.
			List<String> key = Arrays.asList(candidate.category(), candidate.subcategory(), candidate.answer());
			distinct.putIfAbsent(key, candidate);
			if (distinct.size() == topK) {
				break;
			}
		}
		return List.copyOf(distinct.values());
	}
}
