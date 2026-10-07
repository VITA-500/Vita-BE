package com.vita.search.service;

import com.vita.search.dto.FaqRetrievalContext;

/**
 * BE4(Chat/LLM)가 호출하는 RAG 검색 진입점. BE4는 이 인터페이스만 알면 되고,
 * 내부 구현(임베딩 모델, Top-K, threshold 등)이 바뀌어도 영향받지 않는다.
 */
public interface FaqRetrievalService {

	/**
	 * @param query 사용자 질문 원문 (임베딩 변환은 구현체 내부 책임)
	 * @param topK  최대 반환 개수 (권장값 3~5, BE4 프롬프트 예산에 맞춰 조정 가능)
	 * @return 참고 FAQ·요금제 목록과 최고 유사도(topSimilarity)를 담은 컨텍스트. 관련 항목이
	 *         없으면 해당 목록은 비지만 topSimilarity에는 threshold 미달 후보의 최고 점수가 담긴다.
	 */
	FaqRetrievalContext search(String query, int topK);

	/**
	 * 질문을 FAQ용·요금제용으로 이미 나눠 둔 호출자(BE4의 질문 변환)가 한 번에 검색하는 진입점. FAQ는 faqQuery, 요금제는
	 * planQuery로 각각 임베딩해서 검색하므로 같은 질문으로 {@link #search(String, int)}를 두 번 부를 필요가 없다.
	 * 이 경로는 BE3의 질문 변환기를 거치지 않는다(호출자가 이미 변환했으므로).
	 *
	 * <p>무관 질문 규칙(개인 정보 조회·타사 비교)과 분류 이름 가산점은 변환된 질문이 아니라 {@code originalQuery}로 판정한다.
	 * 요금제 조건(가격·데이터량·대상)은 originalQuery와 planQuery 양쪽에서 읽어 합친다.
	 *
	 * @param originalQuery 사용자 질문 원문
	 * @param faqQuery      FAQ 검색용 질문. null이면 FAQ 검색을 건너뛰고 FAQ 결과는 빈 목록이다.
	 * @param planQuery     요금제 검색용 질문. null이면 요금제 검색을 건너뛰고 요금제 결과는 빈 목록이다.
	 * @param topK          FAQ 최대 반환 개수(요금제 개수는 별도 설정을 따른다)
	 * @return 둘 다 null이면 빈 컨텍스트
	 */
	FaqRetrievalContext search(String originalQuery, String faqQuery, String planQuery, int topK);
}
