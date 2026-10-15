package com.vita.search.pipeline;

import java.util.Locale;

/**
 * 검색 한 번의 단계별 소요 시간(나노초). BE4의 Trace/Span에 검색 단계 시간으로 그대로 넘길 수 있다.
 *
 * <p>각 단계가 무엇을 포함하는지는 {@link Stage}의 설명을 따른다. 단계 시간의 합이 전체 시간이다
 * (단계 사이의 사소한 연결 시간은 직전 단계에 포함된다).
 *
 * @param transformNanos 질문 변환
 * @param embeddingNanos 임베딩
 * @param faqSearchNanos FAQ 후보 검색
 * @param planSearchNanos 요금제 검색
 * @param contextNanos   Context 구성
 */
public record StageTimings(
		long transformNanos,
		long embeddingNanos,
		long faqSearchNanos,
		long planSearchNanos,
		long contextNanos) {

	/** 측정하는 단계. */
	public enum Stage {
		/** 질문 변환({@link QueryTransformer}). 기본 구현은 변환이 없어 거의 0이다. LLM을 쓰는 변환은 호출 시간이 여기에 잡힌다. */
		TRANSFORM("질문 변환"),
		/** 임베딩 서버 호출. FAQ용과 요금제용 질문이 다르면 두 번 호출한 시간의 합이다. */
		EMBEDDING("임베딩"),
		/** FAQ 후보 검색({@link FaqRetriever}). DB 조회 시간이다. */
		FAQ_SEARCH("FAQ 검색"),
		/** 요금제 검색(벡터 검색과 조건 매칭 조회). */
		PLAN_SEARCH("요금제 검색"),
		/** 후처리와 응답 구성: 무관 질문 규칙, 분류 가산점, 중복 제거, topK, threshold 판정, 응답 객체 변환. */
		CONTEXT("Context 구성");

		private final String label;

		Stage(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	/** 단계의 소요 시간(나노초). */
	public long nanosOf(Stage stage) {
		return switch (stage) {
			case TRANSFORM -> transformNanos;
			case EMBEDDING -> embeddingNanos;
			case FAQ_SEARCH -> faqSearchNanos;
			case PLAN_SEARCH -> planSearchNanos;
			case CONTEXT -> contextNanos;
		};
	}

	/** 전체 소요 시간(나노초). */
	public long totalNanos() {
		return transformNanos + embeddingNanos + faqSearchNanos + planSearchNanos + contextNanos;
	}

	/** 나노초를 밀리초(소수점 포함)로 바꾼다. */
	public static double toMillis(long nanos) {
		return nanos / 1_000_000.0;
	}

	/** 로그용 한 줄 요약. 예: "질문 변환 0.0, 임베딩 41.2, FAQ 검색 12.8, 요금제 검색 9.5, Context 구성 0.4, 전체 63.9 (ms)". */
	public String summary() {
		StringBuilder sb = new StringBuilder();
		for (Stage stage : Stage.values()) {
			sb.append(stage.label()).append(' ').append(String.format(Locale.ROOT, "%.1f", toMillis(nanosOf(stage)))).append(", ");
		}
		sb.append("전체 ").append(String.format(Locale.ROOT, "%.1f", toMillis(totalNanos()))).append(" (ms)");
		return sb.toString();
	}
}
