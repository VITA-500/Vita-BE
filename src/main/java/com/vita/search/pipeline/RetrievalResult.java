package com.vita.search.pipeline;

import com.vita.search.dto.FaqRetrievalContext;
import com.vita.search.dto.PlanSimilarityResult;
import com.vita.search.service.IrrelevantQueryDetector;
import com.vita.search.service.PlanSearchService;
import java.util.List;
import java.util.Set;

/**
 * 검색 한 번의 전체 결과. BE4가 쓰는 최종 응답({@link #context()})과, 그 응답이 만들어진 과정(변환된 질문, 후보 풀과
 * 중간 결과, threshold 판정, 단계별 시간)을 함께 담는다. 평가 러너와 BE4의 Trace/Span이 이 과정 정보를 쓴다.
 *
 * @param query           질문 변환 결과(원문과 FAQ·요금제 검색에 쓴 질문)
 * @param faq             FAQ 후보 풀과 최종 결과를 고른 과정
 * @param planOutcome     요금제 검색 결과(후보 목록과 조건 매칭 여부, threshold 적용 전). 요금제를 검색하지 않았으면 빈 결과
 * @param planResults     요금제 threshold까지 적용한 최종 요금제 결과(무관 질문 규칙 적용 전)
 * @param planTopSimilarity 요금제 후보의 최고 유사도(조건 매칭이면 threshold 이상으로 올린 값)
 * @param topSimilarity   FAQ·요금제를 통틀어 가장 높은 유사도(BE4에 알리는 값)
 * @param irrelevantRules 걸린 무관 질문 규칙. 비어 있지 않으면 {@code context}의 FAQ·요금제는 비워진다.
 * @param timings         단계별 소요 시간
 * @param context         BE4에 전달하는 최종 응답
 */
public record RetrievalResult(
		TransformedQuery query,
		FaqSelection faq,
		PlanSearchService.PlanSearchOutcome planOutcome,
		List<PlanSimilarityResult> planResults,
		double planTopSimilarity,
		double topSimilarity,
		Set<IrrelevantQueryDetector.Rule> irrelevantRules,
		StageTimings timings,
		FaqRetrievalContext context) {
}
