package com.vita.search.eval;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 요금제 검색 평가셋(plan_eval_v1)의 질문 한 건과 그 질문의 정답 요금제 목록.
 *
 * <p>FAQ 평가셋과 달리 정답 단위가 요금제 하나(plan_code)다. 정답 관련도는 다음 뜻이다.
 * <ul>
 *   <li>3점 — 질문 조건을 모두 만족하고 질문자가 그대로 가입할 수 있는 요금제(대상 그룹을 말한 질문이면 그 그룹의 요금제)</li>
 *   <li>2점 — 조건은 만족하지만 연령 제한 요금제(청년·시니어·키즈)라 해당될 때만 가입 가능하거나, 서술형 질문에서 쓸 수는 있으나
 *       요금제 설명이 직접 겨냥하지 않는 경우</li>
 *   <li>1점 — 조건은 만족하지만 기기 전용(워치·태블릿) 요금제라, 기기를 말하지 않은 질문에는 부적절한 경우</li>
 * </ul>
 * 목록에 없는 요금제는 0점이다. 정답이 없는 질문은 두 종류다. {@code expectNone}은 요금제 상품 질문이 아니라 요금제 결과가
 * 나오면 안 되는 질문이고, {@code noExactMatch}는 조건에 정확히 맞는 요금제가 없어 {@code alternatives}를 안내하는 질문이다.
 *
 * @param qid          질문 번호(P001 형식)
 * @param query        사용자가 입력할 질문 원문
 * @param style        질문 말투(FORMAL, CASUAL, SHORT, SITUATION, TYPO, NEGATION)
 * @param type         질문 유형(NAME, PRICE_EXACT, PRICE_RANGE, DATA_AMOUNT, UNLIMITED, TARGET_GROUP, VOICE_SMS, COMBO,
 *                     EXTREME, SITUATION, NO_EXACT, NONE)
 * @param sortKey      최상급 질문의 정렬 기준({@code PlanSortKey} 이름). 최상급이 아니면 null
 * @param expectNone   요금제 결과가 없어야 하는 질문인지
 * @param noExactMatch 질문 조건에 정확히 맞는 요금제가 없는 질문인지
 * @param knownGap     현재 검색이 지원하지 않는 것으로 알려진 조합(예: 가격 범위 + 최상급)인지
 * @param fuzzy        정답 판단이 갈릴 수 있어 검수 때 특히 확인해야 하는 질문인지
 * @param relevance    관련도("3","2","1") → 요금제 코드 목록
 * @param alternatives 정확히 맞는 요금제가 없을 때 안내할 가까운 요금제
 * @param condition    정답을 이렇게 정한 근거(사람이 읽는 설명)
 * @param note         검수자를 위한 메모
 * @param subsetOrder  앞에서 N개로 잘라 쓸 때의 순서(1부터). 유형이 고르게 섞여 있다.
 */
public record PlanEvalQuestion(
		String qid,
		String query,
		String style,
		String type,
		String sortKey,
		boolean expectNone,
		boolean noExactMatch,
		boolean knownGap,
		boolean fuzzy,
		Map<String, List<String>> relevance,
		List<String> alternatives,
		String condition,
		String note,
		int subsetOrder) {

	/** 관련도 높은 순서. */
	private static final List<Integer> GRADES = List.of(3, 2, 1);

	/** 요금제 코드 → 관련도. 정답 목록에 없는 요금제는 맵에 없다(= 0점). */
	public Map<String, Integer> gradeByCode() {
		Map<String, Integer> grades = new HashMap<>();
		for (int grade : GRADES) {
			for (String code : codes(grade)) {
				grades.merge(code, grade, Math::max);
			}
		}
		return grades;
	}

	/** 관련도가 minGrade 이상인 요금제 코드 집합. */
	public Set<String> relevantCodes(int minGrade) {
		Set<String> result = new LinkedHashSet<>();
		for (int grade : GRADES) {
			if (grade >= minGrade) {
				result.addAll(codes(grade));
			}
		}
		return result;
	}

	private List<String> codes(int grade) {
		return relevance == null ? List.of() : relevance.getOrDefault(String.valueOf(grade), List.of());
	}
}
