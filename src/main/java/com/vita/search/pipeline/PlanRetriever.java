package com.vita.search.pipeline;

import com.vita.search.dto.PlanSimilarityResult;
import java.util.List;

/**
 * 요금제 검색의 후보 단계: 질문에 맞는 요금제 후보를 순위대로 가져온다. FAQ의 {@link FaqRetriever}와 같은 자리다.
 *
 * <p>기본 구현은 pgvector 코사인 유사도 검색({@link VectorPlanRetriever})이다. 영어 컬럼 검색이나 Hybrid 검색(BM25 + 벡터)은
 * 이 인터페이스를 구현한 빈을 추가하고 {@code search.pipeline.plan-retriever}(서비스) 또는
 * {@code search.plan-eval.plan-retriever}(요금제 평가 러너)에 그 빈 이름을 적어서 끼운다.
 *
 * <p>요금제 검색은 이 후보 위에서 질문 조건(가격·데이터량·대상 등) 매칭을 한다({@code PlanSearchService}). 조건 매칭이 되면
 * 후보에서 조건을 만족하는 요금제만 골라 가격순 또는 유사도순으로 다시 정렬하고, 조건이 없거나 매칭이 안 되면 이 후보의 순서가
 * 그대로 앞에서부터 쓰인다.
 *
 * <p>구현체가 지킬 약속은 {@link FaqRetriever}와 같다.
 * <ol>
 *   <li><b>후보 순서</b>는 그 검색 방식의 최종 순위다(가장 관련 있는 후보가 앞).</li>
 *   <li><b>{@code similarity}</b>는 항상 질문 벡터와 요금제 문서 벡터의 코사인 유사도(0~1)다. 요금제 threshold 판정과 BE4에 알리는
 *       유사도, 조건 매칭 결과의 유사도순 정렬이 이 값을 쓴다. Hybrid의 RRF 점수처럼 단위가 다른 점수는 넣지 않고, 키워드 검색으로만
 *       올라온 후보도 코사인 유사도를 계산해서 채운다.</li>
 *   <li><b>{@code id}, {@code planCode}</b>와 요금제 속성(월 요금, 대상, 데이터·통화·문자 정책 등)은 원본 요금제({@code plans})의
 *       값이다. 영어 테이블에서 찾았더라도 조건 매칭과 정답지가 쓰는 원본 값을 채운다.</li>
 * </ol>
 * 후보는 {@code poolSize}개까지 가져온다. 요금제는 15종 규모라 서비스 기본값은 전부가 들어오는 크기다.
 */
public interface PlanRetriever {

	/**
	 * @param query    질문 텍스트(요금제용 질문)와 그 임베딩 벡터
	 * @param poolSize 가져올 후보의 최대 개수
	 * @return 순위가 매겨진 후보(최대 poolSize개). 후보가 없으면 빈 목록.
	 */
	List<PlanSimilarityResult> retrieve(RetrievalQuery query, int poolSize);
}
