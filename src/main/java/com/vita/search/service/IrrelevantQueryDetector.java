package com.vita.search.service;

import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 질문 문장을 규칙(정규식)만으로 훑어 FAQ 검색 대상이 아닐 가능성이 높은 유형(개인 정보 조회, 타사 관련)을
 * 판별한다. LLM 호출 없이 상태 없는 순수 함수라 DB 없이 단위 테스트가 된다.
 *
 * <p>{@code RetrievalPipeline}이 {@code retrieval.irrelevant-rule.enabled}가 켜져 있을 때 원문 질문에 적용하고, 걸리면
 * FAQ·요금제 컨텍스트를 비워 BE4에 "관련 없음"으로 전달한다. 규칙을 고칠 때는 "무관 질문을 얼마나 잡는지"와
 * "정상 질문을 얼마나 잘못 잡는지"를 함께 본다(회귀 러너의 규칙 검증 세트, 평가셋 질문, FAQ 질문 원문). 그래서 결과를
 * 하나의 판정이 아니라 규칙 단위({@link Rule})로 돌려준다.
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

	/**
	 * 1인칭 표현. 조사가 붙은 "내가/제가/나는/저는"도 포함하고, "우리"는 "우리 동네"처럼 지역을 뜻해서 제외한다.
	 * "나"·"저"는 "큰일 나?"처럼 동사로도 쓰여서, 문장 맨 앞이거나 "이번·지금" 같은 시점 표현 앞에 올 때만 1인칭으로 본다.
	 */
	private static final Pattern FIRST_PERSON = Pattern.compile(
			"(?<![가-힣])(?:내가|제가|나는|저는|내|제)(?![가-힣])"
					+ "|(?:^\\s*|[.?!]\\s*)(?:나|저)(?![가-힣])"
					+ "|(?<![가-힣])(?:나|저)(?= ?(?:이번|지금|요즘|오늘|어제|최근|방금|지난|올해|작년|아직|벌써))");

	/**
	 * 실제 값이나 상태를 묻는 조회 표현. "내역/이력"은 "청구 내역이 제 내역과 함께 보여요"처럼 문제 상황을
	 * 설명하는 정상 질문에도 나와서 단독으로는 넣지 않았다("내역 보여줘"는 "보여줘"로 잡힌다).
	 */
	private static final Pattern LOOKUP = Pattern.compile(
			"얼마|몇|언제|뭐야|뭐예요|뭔가요|뭔지|뭐로|상태|보여줘|남았|나왔|썼|쓰고 ?있|받고 ?있|가입(?:된|한|돼|되어)|등록(?:돼|되)"
					+ "|만료|현황|알려줘|알려 ?주세요|궁금|확인해 ?줘|확인해 ?주세요|조회해 ?(?:줘|주세요)|잔액");

	/**
	 * 방법·절차·위치를 묻는 행동 질문. 이런 질문은 FAQ가 답할 수 있어 개인 정보 조회로 보지 않는다.
	 * "확인하는 곳이 어디예요?"처럼 어디서 확인하는지 묻는 질문도 FAQ("…어디서 확인하나요?")가 답한다.
	 */
	private static final Pattern HOW_TO = Pattern.compile(
			"방법|어떻게 (?:하|해|신청|변경|바꿔|신고|처리|취소|등록|받|발급|확인|조회|열람|얻|이용|사용)"
					+ "|신고|절차|하려면|하고 ?싶|할 ?수 ?있|가능|어디");

	/**
	 * 요금이 언제 빠지는지, 언제 적용되는지처럼 제도의 시점을 묻는 표현. "내 통신요금에서 언제 빠져?"는 계정의 실제 값을
	 * 묻는 것이 아니라 정책을 묻는 질문이라 FAQ가 답한다. 실제 값을 묻는 "계약 만료일이 언제야?"와 구분한다.
	 */
	private static final Pattern POLICY_TIMING = Pattern.compile(
			"언제 ?(?:까지|부터)? ?(?:빠|적용|청구|환불|삭제|해지|반영|처리|발송|들어|바뀌|끝나|풀리|차단|정지|취소|결제)");

	/** 구체적인 타사 이름. 영문은 다른 단어의 일부(예: KTX)와 헷갈리지 않도록 앞뒤 알파벳을 제외한다. */
	private static final Pattern BRAND = Pattern.compile(
			"(?<![A-Za-z])(?:SKT|KT|SK)(?![A-Za-z])|SK텔레콤|에스케이|케이티|(?<![A-Za-z])U\\+|LG ?U\\+|LG유플러스|엘지유플러스"
					+ "|알뜰폰|통신 ?3사|타사|경쟁사|통신사 ?(?:끼리|별|마다)");

	/**
	 * 일반적인 타사 표현. "다른 통신사에서 옮겨오려면…"(번호이동)은 정상 질문이라, 비교·혜택 같은 표현이 함께
	 * 있을 때만 잡는다. "통신사 비교"는 그 자체로 비교 질문이다.
	 */
	private static final Pattern GENERIC_COMPETITOR = Pattern.compile(
			"(?:타 ?통신사|다른 ?통신사)(?=.*(?:비교|혜택|프로모션|싸다|싸지|더 ?싸|저렴|비싸|이득|나은|장점|차이))|통신사 ?비교");

	/**
	 * 타사 이름이 나와도 VITA의 번호이동·호환·이용 가능 여부를 묻는 표현("SKT에서 번호이동 하려면?", "알뜰폰 가입자도
	 * 영상통화 되나요?"). 이런 질문은 FAQ가 답한다.
	 */
	private static final Pattern MIGRATION_OR_COMPATIBILITY = Pattern.compile(
			"번호 ?이동|옮겨|옮기|옮길|넘어오|넘어와|가능|이용할 ?수|사용할 ?수|쓸 ?수|보낼 ?수|되나요|돼요");

	/** 타사의 요금·혜택을 묻거나 VITA와 견주는 표현. 이 표현이 함께 있으면 호환 질문으로 보지 않고 타사 질문으로 막는다. */
	private static final Pattern COMPARE_OR_PRICE = Pattern.compile(
			"요금|가격|추천|비교|혜택|프로모션|얼마|할인|이벤트|싸|저렴|비싸|약정|위약금|결합|이득|나은|낫|차이|어때|좋아|좋은|대비");

	/** VITA 이용 품질을 타사와 견주어 불편을 말하는 문장("타사 보다 느린 것 같아요"). FAQ(속도·품질)가 답한다. */
	private static final Pattern QUALITY_COMPLAINT = Pattern.compile(
			"(?:타사|다른 ?통신사) ?보다.{0,10}(?:느리|느린|느려|느립|끊기|끊겨|불안정)");

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

		if (FIRST_PERSON.matcher(query).find() && LOOKUP.matcher(query).find() && !HOW_TO.matcher(query).find()
				&& !POLICY_TIMING.matcher(query).find()) {
			rules.add(Rule.PERSONAL_LOOKUP);
		}
		if (BRAND.matcher(query).find() && !isMigrationOrComplaint(query)) {
			rules.add(Rule.COMPETITOR_BRAND);
		}
		if (GENERIC_COMPETITOR.matcher(query).find()) {
			rules.add(Rule.COMPETITOR_GENERIC);
		}
		return rules;
	}

	/**
	 * 타사 이름이 있어도 번호이동·호환 가능 여부를 묻거나(요금·혜택 비교 표현이 없을 때) 타사보다 느리다는 불편을 말하는
	 * 질문인지 본다. 이런 질문은 타사 정보를 묻는 것이 아니라 VITA 이용에 관한 것이다.
	 */
	private static boolean isMigrationOrComplaint(String query) {
		boolean migrationOrCompatibility = MIGRATION_OR_COMPATIBILITY.matcher(query).find()
				&& !COMPARE_OR_PRICE.matcher(query).find();
		return migrationOrCompatibility || QUALITY_COMPLAINT.matcher(query).find();
	}
}
