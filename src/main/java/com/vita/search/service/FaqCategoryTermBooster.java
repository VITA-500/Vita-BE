package com.vita.search.service;

import com.vita.search.dto.FaqSimilarityResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 질문에 FAQ 분류(카테고리·세부분류) 이름과 같은 상품·서비스 단어가 있으면, 그 분류의 FAQ 후보에 가산점을 줘서
 * 순위를 다시 매긴다.
 *
 * <p>왜 필요한가: 임베딩 유사도는 "IPTV 우리동네서 되나?"의 핵심 단어인 IPTV를 충분히 무겁게 보지 않아서,
 * 소상공인 IPTV나 설치장소 변경 같은 이웃 분류가 1등이 되는 일이 있다. 질문에 "IPTV", "유선", "소상공인"처럼 정답
 * 분류의 이름이 그대로 들어 있을 때는 그 분류를 약간 앞세우는 편이 정확하다.
 *
 * <p>가산점은 순위를 정하는 데만 쓴다. 후보의 유사도 값 자체는 바꾸지 않으므로 threshold 판단과 BE4에 전달하는
 * 점수는 그대로다. 단어는 위에서부터(더 구체적인 것부터) 차례로 찾고, 찾은 단어는 지워서 더 짧은 단어가 같은
 * 글자를 다시 세지 않게 한다(예: "인터넷전화"를 찾은 뒤에는 "인터넷"으로 또 세지 않는다).
 */
public final class FaqCategoryTermBooster {

	/** 질문에서 찾을 단어 패턴과, 그 단어가 있을 때 가산점을 받을 후보 조건. */
	private record Rule(Pattern pattern, Predicate<FaqSimilarityResult> favors) {
	}

	/** 더 구체적인 단어가 앞에 오도록 정렬했다. 순서가 중복 계산을 막는다. */
	private static final List<Rule> RULES = List.of(
			rule("인터넷 ?전화", sub -> sub.contains("인터넷전화")),
			rule("IPTV|아이피티비", sub -> sub.contains("IPTV")),
			rule("유무선", sub -> sub.equals("유무선 결합")),
			rule("CCTV|씨씨티비|시씨티비", sub -> sub.equals("CCTV")),
			rule("수신자 ?부담", sub -> sub.equals("수신자 부담전화")),
			rule("WCDMA|GSM|구형 ?통신망", sub -> sub.equals("WCDMA/GSM")),
			categoryRule("소상공인|상가|가게|사업장", "소상공인"),
			rule("유선", sub -> sub.equals("유선 결합")),
			rule("무선", sub -> sub.equals("무선 결합")),
			categoryRule("결합", "결합 할인"),
			rule("인터넷", sub -> sub.contains("인터넷") && !sub.contains("인터넷전화")),
			rule("(?<![A-Za-z])TV|티비", sub -> sub.equals("TV")),
			new Rule(Pattern.compile("유심|USIM", Pattern.CASE_INSENSITIVE), r -> r.category().startsWith("유심")),
			categoryRule("로밍", "해외로밍"),
			rule("군입대|일시정지", sub -> sub.equals("군입대 일시정지")),
			// "인터넷 설치장소 변경"처럼 "인터넷"과 함께 쓰이는 이름 없는 분류(이름에 "인터넷"이 없음)를 같이 앞세워야
			// 일반 단어 "인터넷"이 이 분류의 FAQ를 밀어내지 않는다.
			rule("설치 ?장소|이사|주소", sub -> sub.equals("설치장소 변경")));

	private FaqCategoryTermBooster() {
	}

	private static Rule rule(String regex, Predicate<String> subcategoryMatches) {
		return new Rule(Pattern.compile(regex, Pattern.CASE_INSENSITIVE),
				result -> result.subcategory() != null && subcategoryMatches.test(result.subcategory()));
	}

	private static Rule categoryRule(String regex, String category) {
		return new Rule(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), result -> category.equals(result.category()));
	}

	/**
	 * 질문에 들어 있는 분류 단어마다 bonus를 더한 점수로 후보를 다시 정렬한다.
	 *
	 * @param candidates 유사도 내림차순 후보(예: 후보 풀). 유사도 값은 바꾸지 않는다.
	 * @param bonus      단어 하나가 분류와 일치할 때 더할 가산점(유사도와 같은 0~1 척도). 0 이하면 순서를 바꾸지 않는다.
	 * @return 가산점 기준으로 다시 정렬한 후보. 질문에 분류 단어가 없으면 입력 순서 그대로다.
	 */
	public static List<FaqSimilarityResult> rerank(String query, List<FaqSimilarityResult> candidates, double bonus) {
		if (query == null || candidates.isEmpty() || bonus <= 0) {
			return candidates;
		}

		List<Predicate<FaqSimilarityResult>> hits = new ArrayList<>();
		String remaining = query;
		for (Rule rule : RULES) {
			Matcher matcher = rule.pattern().matcher(remaining);
			if (matcher.find()) {
				hits.add(rule.favors());
				remaining = matcher.replaceAll(" ");
			}
		}
		if (hits.isEmpty()) {
			return candidates;
		}

		// Stream.sorted는 정렬 기준이 같으면 입력 순서를 유지한다(원래 유사도 순).
		return candidates.stream()
				.sorted(Comparator.comparingDouble((FaqSimilarityResult candidate) -> boostedScore(candidate, hits, bonus))
						.reversed())
				.toList();
	}

	private static double boostedScore(FaqSimilarityResult candidate, List<Predicate<FaqSimilarityResult>> hits, double bonus) {
		long matched = hits.stream().filter(hit -> hit.test(candidate)).count();
		return candidate.similarity() + bonus * matched;
	}
}
