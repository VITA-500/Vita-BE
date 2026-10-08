package com.vita.search.hybrid;

/**
 * 벡터 순위와 키워드(BM25) 순위를 합치는 가중 RRF의 설정.
 *
 * <p>문서의 점수는 {@code 벡터 가중치 / (rrfK + 벡터 순위) + 키워드 가중치 / (rrfK + 키워드 순위)}이고, 한쪽 검색에 없는 문서는
 * 그 항이 0이다. FAQ는 의미가 중요해 벡터 쪽, 요금제는 상품명·키워드가 중요해 키워드 쪽 가중치를 크게 두는 식으로 도메인마다 따로 쓴다.
 *
 * @param vector  벡터 순위의 가중치(0 이상)
 * @param keyword 키워드(BM25) 순위의 가중치(0 이상). 둘 다 0일 수는 없다
 * @param rrfK    순위 감쇠 상수(1 이상). 값이 클수록 순위 차이가 점수에 덜 반영된다
 */
public record HybridWeights(double vector, double keyword, int rrfK) {

	public HybridWeights {
		if (vector < 0 || keyword < 0 || Double.isNaN(vector) || Double.isNaN(keyword)) {
			throw new IllegalArgumentException("Hybrid 가중치는 0 이상이어야 합니다: vector=" + vector + ", keyword=" + keyword);
		}
		if (vector == 0 && keyword == 0) {
			throw new IllegalArgumentException("Hybrid 가중치가 둘 다 0일 수는 없습니다.");
		}
		if (rrfK < 1) {
			throw new IllegalArgumentException("RRF의 k는 1 이상이어야 합니다: " + rrfK);
		}
	}
}
