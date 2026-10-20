package com.vita.trace;

/**
 * Trace 안의 단계(Span) 종류. {@link #code()}가 spans.type 컬럼에 저장되는 값이다.
 * 챗봇 처리 흐름(ChatAnswerStreamer.run)의 단계와 같은 순서로 정의했다.
 */
public enum SpanType {

	/** 질문 변환(LLM 1회). */
	TRANSFORM("transform"),
	/** FAQ 검색. */
	FAQ_RETRIEVE("faq_retrieve"),
	/** 요금제 검색. */
	PLAN_RETRIEVE("plan_retrieve"),
	/** 최상급(극값) 질문의 정형 조회. */
	PLAN_LOOKUP("plan_lookup"),
	/** 매장 질문의 매장 조회. */
	STORE_LOOKUP("store_lookup"),
	/** 답변 생성(최종 프롬프트 + 답변). */
	GENERATION("generation");

	private final String code;

	SpanType(String code) {
		this.code = code;
	}

	/** DB에 저장하는 종류 이름. */
	public String code() {
		return code;
	}
}
