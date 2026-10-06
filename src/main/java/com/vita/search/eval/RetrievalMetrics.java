package com.vita.search.eval;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 검색 결과를 정답지와 비교해 점수를 내는 순수 계산 모음. DB나 임베딩 서버 없이 단위 테스트할 수 있다.
 *
 * <p>정답 판정 기준: 풀(후보 N개)에 정답이 들어왔는지(Recall)는 관련도 1점 이상을, 상위 3개의 품질
 * (Precision, MRR, Hit)은 관련도 2점 이상을 정답으로 본다. nDCG는 3/2/1점을 그대로 이득으로 쓴다.
 */
public final class RetrievalMetrics {

	private RetrievalMetrics() {
	}

	/**
	 * 정답 중 몇 개를 가져왔는지의 비율.
	 *
	 * @param relevantIds  정답 FAQ ID 집합
	 * @param retrievedIds 검색으로 가져온 FAQ ID
	 * @return 정답이 하나도 없으면 0
	 */
	public static double recall(Set<String> relevantIds, Collection<String> retrievedIds) {
		if (relevantIds.isEmpty()) {
			return 0.0;
		}
		long hit = retrievedIds.stream().distinct().filter(relevantIds::contains).count();
		return (double) hit / relevantIds.size();
	}

	/**
	 * 정답 답변 묶음 중 몇 개를 가져왔는지의 비율. 묶음 안의 FAQ(원문과 변형) 중 하나라도 가져오면 그 묶음은 찾은 것으로 센다.
	 * 검색이 같은 답변의 후보를 하나로 합치기 때문에, 최종 상위 K개의 Recall은 FAQ ID가 아니라 묶음 단위로 재야 공정하다.
	 *
	 * @param relevantGroups 정답 답변 묶음 목록(각 묶음은 같은 답변을 가진 FAQ ID 목록)
	 * @param retrievedIds   검색으로 가져온 FAQ ID
	 * @return 정답 묶음이 하나도 없으면 0
	 */
	public static double groupRecall(List<List<String>> relevantGroups, Collection<String> retrievedIds) {
		if (relevantGroups.isEmpty()) {
			return 0.0;
		}
		Set<String> retrieved = Set.copyOf(retrievedIds);
		long found = relevantGroups.stream().filter(group -> group.stream().anyMatch(retrieved::contains)).count();
		return (double) found / relevantGroups.size();
	}

	/**
	 * 상위 k개 중 관련도가 minGrade 이상인 결과의 비율. 결과가 k개보다 적어도 분모는 k로 고정한다
	 * (적게 가져온 것도 못 맞힌 것으로 본다).
	 */
	public static double precisionAtK(List<String> topIds, Map<String, Integer> grades, int k, int minGrade) {
		long hit = topIds.stream().limit(k).filter(id -> grades.getOrDefault(id, 0) >= minGrade).count();
		return (double) hit / k;
	}

	/** 처음으로 나온 정답(관련도 minGrade 이상)의 순위의 역수. 상위 결과에 없으면 0. */
	public static double reciprocalRank(List<String> topIds, Map<String, Integer> grades, int minGrade) {
		for (int i = 0; i < topIds.size(); i++) {
			if (grades.getOrDefault(topIds.get(i), 0) >= minGrade) {
				return 1.0 / (i + 1);
			}
		}
		return 0.0;
	}

	/** 상위 k개 안에 정답(관련도 minGrade 이상)이 하나라도 있으면 1, 없으면 0. */
	public static double hitAtK(List<String> topIds, Map<String, Integer> grades, int k, int minGrade) {
		return topIds.stream().limit(k).anyMatch(id -> grades.getOrDefault(id, 0) >= minGrade) ? 1.0 : 0.0;
	}

	/**
	 * 관련도(3/2/1)를 이득으로 쓰는 nDCG. 이득은 관련도 그대로(선형), 순위 할인은 1/log2(순위+1).
	 *
	 * @param topIds              검색 결과 FAQ ID(순위 순, 서로 다른 답변이어야 함)
	 * @param grades              FAQ ID → 관련도
	 * @param idealGroupGradesDesc 정답 답변 묶음들의 관련도를 높은 순으로 나열한 것(이상적인 상위 k개의 이득)
	 * @return 정답이 없으면 0
	 */
	public static double ndcgAtK(List<String> topIds, Map<String, Integer> grades,
			List<Integer> idealGroupGradesDesc, int k) {
		double dcg = 0.0;
		for (int i = 0; i < Math.min(k, topIds.size()); i++) {
			dcg += grades.getOrDefault(topIds.get(i), 0) * discount(i);
		}
		double idcg = 0.0;
		for (int i = 0; i < Math.min(k, idealGroupGradesDesc.size()); i++) {
			idcg += idealGroupGradesDesc.get(i) * discount(i);
		}
		return idcg == 0.0 ? 0.0 : dcg / idcg;
	}

	private static double discount(int zeroBasedRank) {
		return 1.0 / (Math.log(zeroBasedRank + 2) / Math.log(2));
	}

	public static double mean(double[] values) {
		if (values.length == 0) {
			return 0.0;
		}
		double sum = 0.0;
		for (double v : values) {
			sum += v;
		}
		return sum / values.length;
	}

	/** 앞에서부터 size개의 평균. */
	public static double prefixMean(double[] values, int size) {
		int n = Math.min(size, values.length);
		double sum = 0.0;
		for (int i = 0; i < n; i++) {
			sum += values[i];
		}
		return n == 0 ? 0.0 : sum / n;
	}

	/**
	 * 질문을 size개씩 중복을 허용해 무작위로 뽑아 평균을 내는 일을 draws번 반복했을 때 평균들의 표준편차.
	 * 질문 수가 늘수록 값이 줄어드는데, 이 값이 충분히 작아지는 지점이 "믿을 만한 질문 수"의 기준이다.
	 * 같은 seed면 항상 같은 값이 나온다.
	 */
	public static double bootstrapStdOfMean(double[] values, int size, int draws, long seed) {
		if (values.length == 0 || size < 1 || draws < 2) {
			return 0.0;
		}
		Random random = new Random(seed);
		double[] means = new double[draws];
		for (int d = 0; d < draws; d++) {
			double sum = 0.0;
			for (int i = 0; i < size; i++) {
				sum += values[random.nextInt(values.length)];
			}
			means[d] = sum / size;
		}
		return stdDev(means);
	}

	/**
	 * 값들의 백분위수(가장 가까운 순위 방식). 예를 들어 fraction 0.95는 "95%의 값이 이 값 이하"인 값이다.
	 * 응답 시간 p95처럼 느린 쪽 꼬리를 볼 때 쓴다. 값이 없으면 0.
	 *
	 * @param fraction 0 초과 1 이하의 비율
	 */
	public static double percentile(double[] values, double fraction) {
		if (values.length == 0) {
			return 0.0;
		}
		double[] sorted = values.clone();
		java.util.Arrays.sort(sorted);
		int rank = (int) Math.ceil(fraction * sorted.length);
		return sorted[Math.max(rank, 1) - 1];
	}

	/** 표본 표준편차(n-1로 나눔). 값이 2개 미만이면 0. */
	public static double stdDev(double[] values) {
		if (values.length < 2) {
			return 0.0;
		}
		double mean = mean(values);
		double squares = 0.0;
		for (double v : values) {
			squares += (v - mean) * (v - mean);
		}
		return Math.sqrt(squares / (values.length - 1));
	}
}
