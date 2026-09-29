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

	/** 중복을 걷어내고도 topK개를 채울 수 있도록, DB에서 topK의 몇 배를 넉넉히 가져올지. */
	public static final int POOL_MULTIPLIER = 10;

	private FaqCandidateSelector() {
	}

	/** topK개의 서로 다른 답변을 얻기 위해 DB에서 가져와야 할 후보 개수. */
	public static int poolSize(int topK) {
		return topK * POOL_MULTIPLIER;
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
