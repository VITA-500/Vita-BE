package com.vita.search.eval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 요금제 검색 결과(BE4에 전달되는 요금제 목록)를 요금제 평가셋의 정답지와 비교해 점수를 내는 순수 계산 모음.
 * DB나 임베딩 서버 없이 단위 테스트할 수 있다.
 *
 * <p>FAQ 평가와 달리 요금제는 조건에 맞는 요금제가 여러 개일 수 있고(최대 13개), 반환 개수가 질문마다 달라서
 * "상위 K개"보다 "전달된 목록 전체"를 정답지와 비교한다. 정답으로 꼭 나와야 하는 요금제({@link #requiredCodes})와,
 * 나와도 틀리지 않은 요금제(관련도 1점 이상)를 구분한다.
 */
public final class PlanEvalMetrics {

	/** nDCG를 계산할 때 보는 순위 수. 한 질문의 정답 요금제가 최대 13개라 10개면 충분하다. */
	private static final int NDCG_DEPTH = 10;

	private PlanEvalMetrics() {
	}

	/**
	 * 한 질문의 점수. 계산할 수 없는 값은 NaN이며 평균에서 제외한다.
	 *
	 * @param recallAll    정답지에 있는 요금제(관련도 1점 이상) 중 전달된 비율
	 * @param recallStrong 관련도 2점 이상 정답 중 전달된 비율. 2점 이상 정답이 없는 질문(기기 전용만 정답)은 NaN
	 * @param precision    전달된 요금제 중 정답지에 있는 것(관련도 1점 이상)의 비율. 아무것도 전달하지 않았으면 NaN
	 * @param exact        꼭 나와야 하는 요금제를 모두 전달했고 틀린 요금제(0점)는 하나도 없으면 1, 아니면 0
	 * @param hit1         1위로 전달된 요금제가 꼭 나와야 하는 요금제이면 1
	 * @param mrr          꼭 나와야 하는 요금제가 처음 나온 순위의 역수
	 * @param ndcg         관련도를 반영한 nDCG@10
	 * @param delivered    전달된 요금제 개수
	 */
	public record Score(double recallAll, double recallStrong, double precision, double exact, double hit1, double mrr,
			double ndcg, int delivered) {
	}

	/**
	 * 질문에서 꼭 나와야 하는 요금제. 최상급 질문은 가장 좋은 답(3점)만, 그 외 질문은 2점 이상 정답을 쓰고,
	 * 2점 이상이 없으면(기기 전용 요금제만 정답인 질문) 1점 이상을 쓴다.
	 */
	public static Set<String> requiredCodes(PlanEvalQuestion question) {
		if ("EXTREME".equals(question.type())) {
			return question.relevantCodes(3);
		}
		Set<String> strong = question.relevantCodes(2);
		return strong.isEmpty() ? question.relevantCodes(1) : strong;
	}

	/**
	 * 전달된 요금제 목록(순위 순)을 정답지와 비교한다.
	 *
	 * @param question  정답이 있는 질문(요금제 아님·정확히 맞는 요금제 없음 유형은 따로 판정한다)
	 * @param delivered BE4에 전달된 요금제 코드(순위 순)
	 */
	public static Score score(PlanEvalQuestion question, List<String> delivered) {
		Set<String> allowed = question.relevantCodes(1);
		Set<String> strong = question.relevantCodes(2);
		Set<String> required = requiredCodes(question);

		double recallAll = RetrievalMetrics.recall(allowed, delivered);
		double recallStrong = strong.isEmpty() ? Double.NaN : RetrievalMetrics.recall(strong, delivered);
		double precision = delivered.isEmpty() ? Double.NaN
				: (double) delivered.stream().filter(allowed::contains).count() / delivered.size();

		Set<String> deliveredSet = new LinkedHashSet<>(delivered);
		boolean complete = deliveredSet.containsAll(required);
		boolean clean = allowed.containsAll(deliveredSet);
		double exact = complete && clean ? 1.0 : 0.0;

		double hit1 = !delivered.isEmpty() && required.contains(delivered.get(0)) ? 1.0 : 0.0;
		double mrr = 0.0;
		for (int i = 0; i < delivered.size(); i++) {
			if (required.contains(delivered.get(i))) {
				mrr = 1.0 / (i + 1);
				break;
			}
		}

		return new Score(recallAll, recallStrong, precision, exact, hit1, mrr, ndcg(question, delivered), delivered.size());
	}

	/**
	 * 관련도(3/2/1)를 이득으로 쓰는 nDCG@10. 최상급 질문은 가장 좋은 답(3점)만 이득으로 센다.
	 * 최상급의 2점·1점 요금제는 "더 극단적이지만 가입 제한이 있는 대안"이라 정답과 같은 선상에서 순위를 매기지 않는다.
	 */
	private static double ndcg(PlanEvalQuestion question, List<String> delivered) {
		Map<String, Integer> grades = question.gradeByCode();
		if ("EXTREME".equals(question.type())) {
			Map<String, Integer> best = new java.util.HashMap<>();
			grades.forEach((code, grade) -> {
				if (grade == 3) {
					best.put(code, grade);
				}
			});
			grades = best;
		}
		List<Integer> ideal = new ArrayList<>(grades.values());
		ideal.sort(Comparator.reverseOrder());
		return RetrievalMetrics.ndcgAtK(delivered, grades, ideal, NDCG_DEPTH);
	}

	/** 아무것도 전달되지 않아야 하는 질문(요금제 질문 아님)에서 요금제가 하나라도 전달되었는지(누수). */
	public static boolean leaked(List<String> delivered) {
		return !delivered.isEmpty();
	}

	/** 정확히 맞는 요금제가 없는 질문에서, 정답지가 안내 대상으로 지정한 대안이 전달되었는지. */
	public static boolean alternativeDelivered(PlanEvalQuestion question, List<String> delivered) {
		return delivered.stream().anyMatch(question.alternatives()::contains);
	}
}
