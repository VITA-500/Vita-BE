package com.vita.search.regression;

/**
 * FAQ 검색 정확도 회귀 테스트용 질문 한 건. category/subcategory가 이 질문의 정답 세부분류다.
 *
 * @param ambiguous 질문 문장만으로(앞 대화 없이) 정답 세부분류를 하나로 좁힐 서비스·상품 단서가 없으면 true.
 *                  예: "재시작해도 안 되면 어떻게 하나요?"는 인터넷 장애와 IPTV 장애에 똑같이 해당해서 단일 질문 검색으로는
 *                  정답을 특정할 수 없다. 이런 질문은 검색 품질이 아니라 질문의 모호함이 정확도를 깎으므로 따로 집계한다.
 *                  값이 없으면(null) 모호하지 않은 질문으로 본다.
 */
public record FaqRegressionQuery(String category, String subcategory, String style, String query, Boolean ambiguous) {

	/** 모호한 질문인지. 파일에 ambiguous 값이 없으면 false다. */
	public boolean isAmbiguous() {
		return Boolean.TRUE.equals(ambiguous);
	}
}
