package com.vita.search.pipeline;

/**
 * 질문 변환 단계가 질문을 어떻게 다뤘는지의 기록. 평가 러너가 "변환이 실패해서 원문으로 돌아간 질문"을 세고, 변환이 적용된
 * 질문과 원문으로 돌아간 질문의 점수를 따로 보려고 쓴다. 이 값은 기록용이라 검색 동작과 점수에는 영향을 주지 않는다.
 *
 * <p>{@link TransformedQuery}의 FAQ용·요금제용 질문은 변환기가 {@code null}을 돌려줘도 원문으로 채워 넣기 때문에(검색이
 * 끊기지 않게), 질문 문자열만 봐서는 "LLM이 그렇게 바꿨다"와 "실패해서 원문을 썼다"를 구분할 수 없다. 그 구분을 여기에 남긴다.
 *
 * @param kind     변환 결과의 종류
 * @param reason   {@link Kind#FALLBACK_ORIGINAL}일 때의 사유({@code REASON_*} 상수). 그 외에는 빈 문자열
 * @param faqNull  변환기가 FAQ용 질문을 비워 둔 채 돌려줬는지(원문으로 채우기 전의 값). FAQ와 무관한 질문이라는 뜻
 * @param planNull 변환기가 요금제용 질문을 비워 둔 채 돌려줬는지. 요금제와 무관한 질문이라는 뜻
 */
public record TransformInfo(Kind kind, String reason, boolean faqNull, boolean planNull) {

	/** 변환 비활성. */
	public static final String REASON_DISABLED = "DISABLED";
	/** 변환 모델 호출이 예외로 실패. */
	public static final String REASON_CALL_FAILED = "CALL_FAILED";
	/** 변환 모델의 응답이 비었거나 JSON({@code { ... }})이 없음. */
	public static final String REASON_NO_JSON = "NO_JSON";
	/** 응답에서 JSON을 찾았지만 파싱에 실패. */
	public static final String REASON_PARSE_FAILED = "PARSE_FAILED";
	/** 파싱은 됐지만 FAQ용·요금제용 질문이 모두 비어 있어서(무관 질문인지 실패인지 구분할 수 없어) 원문으로 검색. */
	public static final String REASON_BOTH_NULL = "BOTH_NULL";

	/** 변환 결과의 종류. */
	public enum Kind {
		/** 변환기가 질문을 바꾸지 않는 구현(Baseline)이다. */
		IDENTITY,
		/** 변환기가 FAQ용·요금제용 질문을 모두 만들어 줬다. */
		CHANGED,
		/** 한쪽만 만들어 줬다. 비어 있는 쪽은 원문으로 채워 검색한다. */
		PARTIAL_NULL,
		/** 변환에 실패하거나 결과가 없어서 양쪽 모두 원문으로 검색한다. 사유는 {@link #reason()}. */
		FALLBACK_ORIGINAL
	}

	public TransformInfo {
		kind = kind == null ? Kind.IDENTITY : kind;
		reason = reason == null ? "" : reason;
	}

	public static TransformInfo identity() {
		return new TransformInfo(Kind.IDENTITY, "", false, false);
	}

	public static TransformInfo changed() {
		return new TransformInfo(Kind.CHANGED, "", false, false);
	}

	/** 한쪽만 만든 경우. 두 값이 모두 true인 경우는 {@link #fallback}으로 다룬다. */
	public static TransformInfo partialNull(boolean faqNull, boolean planNull) {
		return new TransformInfo(Kind.PARTIAL_NULL, "", faqNull, planNull);
	}

	public static TransformInfo fallback(String reason) {
		return new TransformInfo(Kind.FALLBACK_ORIGINAL, reason, false, false);
	}

	/** 변환기가 실제로 질문을 만들어 준 경우(전부 또는 한쪽). 원문 그대로이거나 폴백이면 false. */
	public boolean applied() {
		return kind == Kind.CHANGED || kind == Kind.PARTIAL_NULL;
	}
}
