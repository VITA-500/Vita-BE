package com.vita.search.pipeline;

import com.vita.search.dto.FaqSimilarityResult;
import java.util.List;

/**
 * 검색 파이프라인의 3단계: 질문에 맞는 FAQ 후보를 순위대로 가져온다.
 *
 * <p>기본 구현은 한국어 컬럼의 pgvector 코사인 유사도 검색({@link VectorFaqRetriever})이다. 영어 컬럼 검색(BE1)이나
 * Hybrid 검색(BE6)은 이 인터페이스를 구현한 빈을 추가하고 {@code search.pipeline.faq-retriever}(서비스) 또는
 * {@code search.eval.faq-retriever}(평가 러너)에 그 빈 이름을 적어서 끼운다.
 *
 * <p>구현체가 지킬 약속은 세 가지다.
 * <ol>
 *   <li><b>후보 순서</b>는 그 검색 방식의 최종 순위다(가장 관련 있는 후보가 앞). 후처리(분류 가산점, 중복 제거, topK, threshold)는
 *       이 순서를 입력으로 받는다.</li>
 *   <li><b>{@code similarity}</b>는 항상 질문 벡터와 후보 문서 벡터의 코사인 유사도(0~1)다. threshold 판정과 BE4에 알리는
 *       {@code topSimilarity}가 이 값을 쓰기 때문이다. Hybrid의 RRF 점수처럼 단위가 다른 점수는 {@code similarity}에 넣지 않는다.
 *       키워드 검색으로만 올라온 후보도 코사인 유사도를 계산해서 채운다.</li>
 *   <li><b>{@code id}</b>는 원본 FAQ의 id({@code faqs.id})다. 영어 테이블(faqs_en)에서 찾았더라도 원본 FAQ id를 돌려줘야
 *       같은 정답지로 채점되고 BE4가 같은 FAQ를 참조한다. 영어로 찾아도 category/subcategory/question/answer는 원본(한국어)
 *       값을 채우는 것을 권장한다(FE1에 표시되는 값이므로).</li>
 * </ol>
 * 후보는 {@code poolSize}개까지 가져온다. 이 값은 최종 결과 개수(topK)와 별개다(같은 답변의 원문·변형이 자리를 다 차지하지
 * 않도록 넉넉히 가져와 후처리에서 줄인다).
 */
public interface FaqRetriever {

	/**
	 * @param query    질문 텍스트와 그 임베딩 벡터
	 * @param poolSize 가져올 후보의 최대 개수
	 * @return 순위가 매겨진 후보(최대 poolSize개). 후보가 없으면 빈 목록.
	 */
	List<FaqSimilarityResult> retrieve(RetrievalQuery query, int poolSize);
}
