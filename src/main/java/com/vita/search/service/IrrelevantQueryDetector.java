package com.vita.search.service;

import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 질문 문장을 규칙(정규식)만으로 훑어 FAQ 검색 대상이 아닐 가능성이 높은 유형(개인 정보 조회, 타사 관련)을
 * 판별한다. LLM 호출 없이 상태 없는 순수 함수라 DB 없이 단위 테스트가 된다.
 *
 * <p>아직 실제 검색에는 연결하지 않았다. 규칙별로 "무관 질문을 얼마나 잡는지"와 "정상 질문을 얼마나 잘못
 * 잡는지"를 회귀 러너로 측정한 뒤, 손익이 맞는 규칙만 연결할지 정한다. 그래서 결과를 하나의 판정이 아니라
 * 규칙 단위({@link Rule})로 돌려준다.
 */
public final class IrrelevantQueryDetector {

	/** 판별 규칙 종류. */
	public enum Rule {
		/** "내 요금 얼마 나왔어?"처럼 본인 계정의 실제 데이터를 묻는 질문. */
		PERSONAL_LOOKUP,
		/** SKT·KT·LG유플러스·알뜰폰·통신 3사처럼 구체적인 타사 이름이 들어간 질문. */
		COMPETITOR_BRAND,
		/** "타사", "다른 통신사"처럼 일반적인 타사 표현. 번호이동 같은 정상 질문에도 나올 수 있어 따로 측정한다. */
		COMPETITOR_GENERIC
	}

	/** 1인칭 표현. 조사가 붙은 "내가/제가/나는/저는"도 포함하고, "우리"는 "우리 동네"처럼 지역을 뜻해서 제외한다. */
	private static final Pattern FIRST_PERSON = Pattern.compile(
			"(?<![가-힣])(?:내가|제가|나는|저는|내|제|나|저)(?![가-힣])");

	/**
	 * 실제 값이나 상태를 묻는 조회 표현. "내역/이력"은 "청구 내역이 제 내역과 함께 보여요"처럼 문제 상황을
	 * 설명하는 정상 질문에도 나와서 단독으로는 넣지 않았다("내역 보여줘"는 "보여줘"로 잡힌다).
	 */
	private static final Pattern LOOKUP = Pattern.compile(
			"얼마|몇|언제|뭐야|뭐예요|뭔가요|뭐로|상태|보여줘|남았|나왔|썼|쓰고 ?있|받고 ?있|가입(?:된|한)|등록(?:돼|되)|만료|현황");

	/** 방법·절차를 묻는 행동 질문. 이런 질문은 FAQ가 답할 수 있어 개인 정보 조회로 보지 않는다. */
	private static final Pattern HOW_TO = Pattern.compile(
			"방법|어떻게 (?:하|해|신청|변경|바꿔)|절차|하려면|하고 ?싶|할 ?수 ?있");

	/** 구체적인 타사 이름. 영문은 다른 단어의 일부(예: KTX)와 헷갈리지 않도록 앞뒤 알파벳을 제외한다. */
	private static final Pattern BRAND = Pattern.compile(
			"(?<![A-Za-z])(?:SKT|KT)(?![A-Za-z])|SK텔레콤|에스케이|케이티|LG ?U\\+|LG유플러스|엘지유플러스|알뜰폰|통신 ?3사");

	/**
	 * 일반적인 타사 표현. "다른 통신사에서 옮겨오려면…"(번호이동)은 정상 질문이라, 비교·혜택 같은 표현이 함께
	 * 있을 때만 잡는다. "통신사 비교"는 그 자체로 비교 질문이다.
	 */
	private static final Pattern GENERIC_COMPETITOR = Pattern.compile(
			"(?:타사|타 ?통신사|다른 ?통신사)(?=.*(?:비교|혜택|프로모션|할인|더 ?싸|싸지|나은|장점|차이))|통신사 ?비교");

	private IrrelevantQueryDetector() {
	}

	/**
	 * 질문에 해당하는 규칙을 모두 찾는다.
	 *
	 * @return 해당하는 규칙 집합. 하나도 없으면 빈 집합
	 */
	public static Set<Rule> detect(String query) {
		Set<Rule> rules = EnumSet.noneOf(Rule.class);
		if (query == null || query.isBlank()) {
			return rules;
		}

		if (FIRST_PERSON.matcher(query).find() && LOOKUP.matcher(query).find() && !HOW_TO.matcher(query).find()) {
			rules.add(Rule.PERSONAL_LOOKUP);
		}
		if (BRAND.matcher(query).find()) {
			rules.add(Rule.COMPETITOR_BRAND);
		}
		if (GENERIC_COMPETITOR.matcher(query).find()) {
			rules.add(Rule.COMPETITOR_GENERIC);
		}
		return rules;
	}
}
