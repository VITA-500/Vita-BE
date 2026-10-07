package com.vita.search.pipeline;

/**
 * 질문 변환 단계({@link QueryTransformer})의 결과. 사용자 원 질문과, FAQ 검색·요금제 검색에 각각 쓸 질문을 담는다.
 *
 * <p>FAQ와 요금제는 찾는 방식이 달라(요금제는 가격·데이터량 같은 조건을 규칙으로 읽는다) 변환 결과를 따로 받는다.
 * 영어 번역, 질문 재작성(Query Transformation) 같은 변형 실험은 이 두 값만 바꿔서 끼운다.
 *
 * @param original  사용자가 입력한 질문 원문. 무관 질문 규칙, 분류 이름 가산점, 로그에는 항상 원문을 쓴다
 *                  (규칙과 분류 이름이 한국어 기준이라 변환된 질문으로는 맞지 않을 수 있다).
 * @param faqQuery  FAQ 검색(임베딩·후보 검색)에 쓸 질문. null이면 "FAQ와 무관한 질문"으로 보고 FAQ 검색을 건너뛴다.
 * @param planQuery 요금제 검색에 쓸 질문. null이면 "요금제와 무관한 질문"으로 보고 요금제 검색을 건너뛴다.
 *                  (요금제 조건은 원문에서도 함께 읽으므로 planQuery가 라벨 형태여도 된다.)
 * @param info      변환이 어떻게 이뤄졌는지의 기록(변환됨/한쪽만 변환/원문으로 폴백). 검색 동작에는 쓰지 않고 평가 기록에만 쓴다.
 */
public record TransformedQuery(String original, String faqQuery, String planQuery, TransformInfo info) {

	public TransformedQuery {
		info = info == null ? TransformInfo.identity() : info;
	}

	/** 변환 기록 없이 만든다(영어 번역 같은 다른 변환기 구현이 쓰던 기존 생성 방식). 기록은 {@link TransformInfo#identity()}가 된다. */
	public TransformedQuery(String original, String faqQuery, String planQuery) {
		this(original, faqQuery, planQuery, TransformInfo.identity());
	}

	/** 변환 없이 원문을 FAQ·요금제 검색에 그대로 쓴다. */
	public static TransformedQuery unchanged(String query) {
		return new TransformedQuery(query, query, query);
	}

	/** FAQ용과 요금제용 질문이 같은지(같으면 임베딩을 한 번만 한다). 둘 중 하나라도 null이면 같지 않다. */
	boolean sameForFaqAndPlan() {
		return faqQuery != null && faqQuery.equals(planQuery);
	}
}
