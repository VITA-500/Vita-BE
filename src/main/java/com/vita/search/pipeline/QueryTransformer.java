package com.vita.search.pipeline;

/**
 * 검색 파이프라인의 1단계: 사용자 질문을 검색에 쓸 질문으로 바꾼다.
 *
 * <p>기본 구현은 아무것도 바꾸지 않는 {@link IdentityQueryTransformer}다. 영어 번역(BE1)이나 LLM 질문 재작성
 * (Query Transformation, BE4)은 이 인터페이스를 구현한 빈을 추가하고
 * {@code search.pipeline.query-transformer}(서비스) 또는 {@code search.eval.query-transformer}(평가 러너)에
 * 그 빈 이름을 적어서 끼운다.
 *
 * <p>구현체가 LLM을 부르는 경우 실패해도 검색이 죽지 않게, 예외 대신 {@link TransformedQuery#unchanged}로 되돌려 주는 것을 권장한다.
 * 이 단계의 소요 시간은 파이프라인이 "질문 변환" 시간으로 따로 잰다.
 */
public interface QueryTransformer {

	/**
	 * @param query 사용자 질문 원문
	 * @return 원문과, FAQ·요금제 검색에 쓸 질문
	 */
	TransformedQuery transform(String query);
}
