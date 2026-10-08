package com.vita.search.hybrid;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Hybrid 검색(BM25 + 벡터, 가중 RRF) 설정({@code search.hybrid.*}). 기본은 꺼져 있고, 켜면 {@code hybridFaqRetriever},
 * {@code hybridPlanRetriever} 빈이 생긴다. BM25는 pg_search 확장이 필요한데 AWS RDS(dev/prod)에는 없으므로 로컬에서만 켠다.
 *
 * @param enabled       Hybrid 검색기 빈을 만들지. dev/prod는 항상 false
 * @param createIndexes 시작할 때 BM25 인덱스를 {@code IF NOT EXISTS}로 만들지(로컬 전용, Flyway에 넣지 않는다)
 * @param faq           FAQ 검색의 결합 설정(기본: 벡터 2 : 키워드 1, k=60, 후보 100)
 * @param plan          요금제 검색의 결합 설정(기본: 벡터 1 : 키워드 2, k=60, 후보 50)
 */
@ConfigurationProperties(prefix = "search.hybrid")
public record HybridProperties(boolean enabled, boolean createIndexes, Side faq, Side plan) {

	public HybridProperties {
		faq = Side.orDefaults(faq, 2.0, 1.0, 60, 100);
		plan = Side.orDefaults(plan, 1.0, 2.0, 60, 50);
	}

	/**
	 * 한 검색(FAQ 또는 요금제)의 결합 설정. 값을 적지 않은 항목은 그 검색의 기본값을 쓴다.
	 *
	 * @param vectorWeight   벡터 가중치
	 * @param keywordWeight  키워드(BM25) 가중치
	 * @param rrfK           RRF의 k
	 * @param candidateLimit 벡터·키워드 검색에서 각각 합칠 후보 수의 상한. 요청한 후보 풀 크기보다 작으면 풀 크기를 쓴다
	 */
	public record Side(Double vectorWeight, Double keywordWeight, Integer rrfK, Integer candidateLimit) {

		static Side orDefaults(Side side, double vectorWeight, double keywordWeight, int rrfK, int candidateLimit) {
			if (side == null) {
				return new Side(vectorWeight, keywordWeight, rrfK, candidateLimit);
			}
			return new Side(
					side.vectorWeight != null ? side.vectorWeight : vectorWeight,
					side.keywordWeight != null ? side.keywordWeight : keywordWeight,
					side.rrfK != null ? side.rrfK : rrfK,
					side.candidateLimit != null ? side.candidateLimit : candidateLimit);
		}

		public HybridWeights weights() {
			return new HybridWeights(vectorWeight, keywordWeight, rrfK);
		}

		/** 후보 풀 크기 이상이 되도록 맞춘 후보 상한. */
		public int candidateLimitFor(int poolSize) {
			return Math.max(candidateLimit, poolSize);
		}
	}
}
