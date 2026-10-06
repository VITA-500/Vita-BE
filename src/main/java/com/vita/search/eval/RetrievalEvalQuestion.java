package com.vita.search.eval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 검색 평가셋(v2)의 질문 한 건과 그 질문의 정답 FAQ 목록.
 *
 * <p>정답은 관련도(3/2/1점)별로 "답변 묶음"의 목록이다. 한 묶음은 같은 답변을 가진 FAQ(원문과 변형 -P1)이며,
 * 검색 로직이 (분류, 세부분류, 답변)이 같은 후보를 하나로 합치기 때문에 nDCG의 이상적인 순서를 묶음 단위로 센다.
 * 목록에 없는 FAQ의 관련도는 0점이다.
 *
 * @param qid            질문 번호(Q001 형식)
 * @param query          사용자가 입력할 질문 원문
 * @param style          질문 말투(FORMAL, CASUAL, SHORT, SITUATION, TYPO, NEGATION)
 * @param seedCategory   이 질문을 만들 때 출발점이 된 FAQ 묶음의 분류
 * @param seedSubcategory 같은 묶음의 세부분류
 * @param seedFaqIds     출발점 FAQ의 ID(원문과 변형)
 * @param subsetOrder    50/100/150/200개로 자를 때 쓰는 순서(1부터). 세부분류 비율이 유지되도록 섞여 있다.
 * @param relevance      관련도("3","2","1") → 답변 묶음 목록(각 묶음은 FAQ ID 목록)
 */
public record RetrievalEvalQuestion(
		String qid,
		String query,
		String style,
		String seedCategory,
		String seedSubcategory,
		List<String> seedFaqIds,
		int subsetOrder,
		Map<String, List<List<String>>> relevance) {

	/** 관련도 높은 순서. */
	private static final List<Integer> GRADES = List.of(3, 2, 1);

	/** FAQ ID → 관련도. 정답 목록에 없는 FAQ는 맵에 없다(= 0점). */
	public Map<String, Integer> gradeById() {
		Map<String, Integer> grades = new HashMap<>();
		for (int grade : GRADES) {
			for (List<String> group : groups(grade)) {
				for (String faqId : group) {
					grades.merge(faqId, grade, Math::max);
				}
			}
		}
		return grades;
	}

	/** 관련도가 minGrade 이상인 FAQ ID 집합. */
	public Set<String> relevantIds(int minGrade) {
		Set<String> ids = new LinkedHashSet<>();
		for (int grade : GRADES) {
			if (grade >= minGrade) {
				groups(grade).forEach(ids::addAll);
			}
		}
		return ids;
	}

	/** 모든 정답 묶음의 관련도를 높은 순으로 나열한다. nDCG의 이상적인 순서(IDCG) 계산에 쓴다. */
	public List<Integer> groupGradesDescending() {
		List<Integer> grades = new ArrayList<>();
		for (int grade : GRADES) {
			for (int i = 0; i < groups(grade).size(); i++) {
				grades.add(grade);
			}
		}
		grades.sort(Comparator.reverseOrder());
		return grades;
	}

	private List<List<String>> groups(int grade) {
		return relevance == null ? List.of() : relevance.getOrDefault(String.valueOf(grade), List.of());
	}
}
