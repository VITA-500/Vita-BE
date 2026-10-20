package com.vita.search.pipeline;

/**
 * 검색 한 번이 어떤 입력과 설정으로 돌았는지의 기록. 검색 결과({@link RetrievalResult})에 실려서 BE3의 Trace/Span 저장에만 쓰이고,
 * 검색 동작과 평가 점수에는 영향을 주지 않는다.
 *
 * @param faqVector         FAQ 검색에 쓴 질문 벡터. FAQ 검색을 하지 않았으면 null
 * @param planVector        요금제 검색에 쓴 질문 벡터. 요금제 검색을 하지 않았으면 null. FAQ와 같은 질문이면 {@code faqVector}와 같은
 *                          객체다(두 번 변환하지 않고 공유하므로, 저장할 때 한 번만 담을 수 있다).
 * @param faqSearched       FAQ 검색을 실제로 했는지(FAQ용 질문이 null이면 false)
 * @param planSearched      요금제 검색을 실제로 했는지
 * @param nonMobileGuard    인터넷·IPTV·TV·로밍 같은 모바일이 아닌 서비스의 요금 질문이라 요금제 검색을 건너뛰었는지
 * @param settings          이 검색에 적용한 설정(threshold, 가산점 등)
 * @param options           이 검색의 실행 옵션(topK, 후보 풀 크기 등)
 */
public record RetrievalTrace(
		float[] faqVector,
		float[] planVector,
		boolean faqSearched,
		boolean planSearched,
		boolean nonMobileGuard,
		RetrievalSettings settings,
		RetrievalOptions options) {
}
