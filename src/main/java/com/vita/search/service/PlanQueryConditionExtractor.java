package com.vita.search.service;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 질문 문장에서 요금제 조건(가격·데이터량·대상 그룹·무제한 여부)을 규칙 기반으로 뽑아낸다. LLM 호출 없이
 * 정규식만 쓰고, 상태가 없는 순수 함수라 DB 없이 단위 테스트가 된다.
 *
 * <p>왜 필요한가: 임베딩은 "3만1천원"과 설명문의 "31,000원"이 같은 값이라는 걸 모르고, "무제한 아니고"
 * 같은 부정도 거의 구분하지 못한다. 이런 정형 조건은 벡터 유사도 대신 plans 테이블의 컬럼과 직접
 * 비교하는 편이 정확하다.
 *
 * <p>오탐을 줄이기 위해 질문에 요금제를 가리키는 말({@code 요금제|플랜|비타})이 있을 때만 동작한다.
 * "로밍 5기가 얼마야?" 같은 FAQ 질문에서 데이터량 조건이 잘못 잡히는 것을 막기 위해서다.
 */
public final class PlanQueryConditionExtractor {

	private static final Pattern PLAN_CONTEXT = Pattern.compile("요금제|플랜|비타");

	/** "3만1천원", "3만원", "5천원"처럼 한글 단위가 섞인 금액. 그룹1=만 단위 수, 그룹2=천 단위 수. */
	private static final Pattern KOREAN_FEE = Pattern.compile("(?:(\\d+)\\s*만)?\\s*(?:(\\d+)\\s*천)?\\s*원");

	/** "31,000원", "31000원"처럼 숫자로만 쓴 금액. */
	private static final Pattern DIGIT_FEE = Pattern.compile("(\\d{1,3}(?:,\\d{3})+|\\d{4,6})\\s*원");

	/** "20기가", "20GB". 5G(네트워크)와 헷갈리지 않도록 단독 "G"는 받지 않고, 소수("1.5기가")는 제외한다. */
	private static final Pattern DATA_AMOUNT = Pattern.compile("(?<![\\d.])(\\d+)\\s*(?:기가바이트|기가|GB|gb|Gb)");

	/** 무제한 부정("무제한 아니고", "무제한은 빼고", "무제한 말고" 등). */
	private static final Pattern UNLIMITED_NEGATED = Pattern.compile(
			"무제한(?:은|이|를|요금제|\\s)*(?:아니|아닌|말고|빼고|제외|없)");

	private static final Pattern UNLIMITED_PARAPHRASE = Pattern.compile("데이터\\s*걱정\\s*없이|마음껏|맘껏");

	private static final Pattern VOICE_OR_SMS = Pattern.compile("통화|문자|전화|음성|SMS|sms");

	private static final Pattern BOUND_MAX = Pattern.compile("^\\s*(?:이하|이내|아래|까지|안쪽)");
	private static final Pattern BOUND_MAX_EXCLUSIVE = Pattern.compile("^\\s*미만");
	private static final Pattern BOUND_MIN = Pattern.compile("^\\s*(?:이상|부터)");
	private static final Pattern BOUND_MIN_EXCLUSIVE = Pattern.compile("^\\s*(?:초과|넘)");
	private static final Pattern BOUND_BAND = Pattern.compile("^\\s*대(?!신|해|비|체|여)");

	/** 금액/데이터 수치 바로 뒤에 붙는 비교 표현을 볼 범위(글자 수). */
	private static final int BOUND_LOOKAHEAD = 6;

	private static final int MB_PER_GB = 1024;
	private static final int FEE_BAND_WIDTH = 9_999;

	private PlanQueryConditionExtractor() {
	}

	/** 수치 뒤 비교 표현의 종류. */
	private enum Bound { EXACT, MAX, MAX_EXCLUSIVE, MIN, MIN_EXCLUSIVE, BAND }

	/** 하나의 수치 조건을 구간으로 바꾼 값(양 끝 포함, null이면 그쪽은 제한 없음). */
	private record Range(Long min, Long max) {
	}

	/**
	 * 질문에서 요금제 조건을 뽑는다.
	 *
	 * @return 추출된 조건. 요금제 관련 질문이 아니거나 조건이 없으면 {@link PlanQueryConditions#isEmpty()}인 값
	 */
	public static PlanQueryConditions extract(String query) {
		if (query == null || !PLAN_CONTEXT.matcher(query).find()) {
			return new PlanQueryConditions(null, null, null, null, null, null);
		}

		Range fee = extractFee(query);
		Range data = extractData(query);

		return new PlanQueryConditions(
				fee == null || fee.min() == null ? null : fee.min().intValue(),
				fee == null || fee.max() == null ? null : fee.max().intValue(),
				data == null ? null : data.min(),
				data == null ? null : data.max(),
				extractTargetGroup(query),
				extractDataPolicy(query));
	}

	/** 금액이 정확히 하나일 때만 조건으로 삼는다(두 개 이상이면 "3만원에서 5만원 사이"처럼 해석이 모호). */
	private static Range extractFee(String query) {
		Long amount = null;
		int end = -1;
		int count = 0;

		Matcher digit = DIGIT_FEE.matcher(query);
		while (digit.find()) {
			count++;
			amount = Long.parseLong(digit.group(1).replace(",", ""));
			end = digit.end();
		}

		Matcher korean = KOREAN_FEE.matcher(query);
		while (korean.find()) {
			if (korean.group(1) == null && korean.group(2) == null) {
				continue;
			}
			// "31,000원"의 "000원"처럼 이미 숫자 금액으로 잡힌 자리는 다시 세지 않는다.
			if (overlapsDigitFee(query, korean.start())) {
				continue;
			}
			count++;
			long man = korean.group(1) == null ? 0 : Long.parseLong(korean.group(1));
			long cheon = korean.group(2) == null ? 0 : Long.parseLong(korean.group(2));
			amount = man * 10_000 + cheon * 1_000;
			end = korean.end();
		}

		if (count != 1) {
			return null;
		}
		return toRange(amount, boundAfter(query, end), true);
	}

	private static boolean overlapsDigitFee(String query, int start) {
		Matcher digit = DIGIT_FEE.matcher(query);
		while (digit.find()) {
			if (start >= digit.start() && start < digit.end()) {
				return true;
			}
		}
		return false;
	}

	/** 데이터량이 정확히 하나일 때만 조건으로 삼는다. 단위는 GB → MB(1GB = 1024MB, plans.base_data_mb 기준). */
	private static Range extractData(String query) {
		Matcher matcher = DATA_AMOUNT.matcher(query);
		Long amountMb = null;
		int end = -1;
		int count = 0;
		while (matcher.find()) {
			count++;
			amountMb = Long.parseLong(matcher.group(1)) * MB_PER_GB;
			end = matcher.end();
		}
		if (count != 1) {
			return null;
		}
		return toRange(amountMb, boundAfter(query, end), false);
	}

	private static Bound boundAfter(String query, int end) {
		String tail = query.substring(end, Math.min(query.length(), end + BOUND_LOOKAHEAD));
		if (BOUND_MAX_EXCLUSIVE.matcher(tail).find()) {
			return Bound.MAX_EXCLUSIVE;
		}
		if (BOUND_MAX.matcher(tail).find()) {
			return Bound.MAX;
		}
		if (BOUND_MIN_EXCLUSIVE.matcher(tail).find()) {
			return Bound.MIN_EXCLUSIVE;
		}
		if (BOUND_MIN.matcher(tail).find()) {
			return Bound.MIN;
		}
		if (BOUND_BAND.matcher(tail).find()) {
			return Bound.BAND;
		}
		return Bound.EXACT;
	}

	/**
	 * @param isFee 금액이면 true. "3만원대"(BAND)는 금액에서만 의미가 있어(3만원~3만9999원), 데이터량에서는 정확 일치로 본다.
	 */
	private static Range toRange(long amount, Bound bound, boolean isFee) {
		return switch (bound) {
			case EXACT -> new Range(amount, amount);
			case MAX -> new Range(null, amount);
			case MAX_EXCLUSIVE -> new Range(null, amount - 1);
			case MIN -> new Range(amount, null);
			case MIN_EXCLUSIVE -> new Range(amount + 1, null);
			case BAND -> isFee ? new Range(amount, amount + FEE_BAND_WIDTH) : new Range(amount, amount);
		};
	}

	/** 대상 그룹 키워드가 정확히 한 그룹만 가리킬 때만 채택한다(여러 그룹이 섞이면 null). */
	private static String extractTargetGroup(String query) {
		Set<String> groups = new LinkedHashSet<>();
		if (Pattern.compile("청년|유스").matcher(query).find()) {
			groups.add("YOUTH");
		}
		if (Pattern.compile("시니어|어르신|부모님").matcher(query).find()) {
			groups.add("SENIOR");
		}
		// "아이폰"/"아이패드"의 "아이"는 어린이가 아니다.
		if (Pattern.compile("키즈|어린이|초등학생|아이(?!폰|패드)").matcher(query).find()) {
			groups.add("KIDS");
		}
		if (Pattern.compile("워치").matcher(query).find()) {
			groups.add("WATCH");
		}
		if (Pattern.compile("태블릿").matcher(query).find()) {
			groups.add("TABLET");
		}
		// "일반적인"의 "일반"은 대상 그룹 표현이 아니다.
		if (Pattern.compile("일반(?!적)").matcher(query).find()) {
			groups.add("GENERAL");
		}
		return groups.size() == 1 ? groups.iterator().next() : null;
	}

	/**
	 * "무제한 아니고/빼고/말고"는 LIMITED, 그냥 "무제한"은 UNLIMITED. 다만 통화·문자를 함께 언급하면
	 * "통화 무제한"처럼 데이터가 아닌 음성/문자 무제한을 뜻할 수 있어 데이터 조건으로 삼지 않는다.
	 */
	private static String extractDataPolicy(String query) {
		// "데이터 걱정 없이", "마음껏"처럼 "무제한"이라는 단어 없이 같은 뜻을 말하는 표현.
		if (UNLIMITED_PARAPHRASE.matcher(query).find() && !query.contains("무제한")) {
			return "UNLIMITED";
		}
		if (!query.contains("무제한")) {
			return null;
		}
		if (UNLIMITED_NEGATED.matcher(query).find()) {
			return "LIMITED";
		}
		if (VOICE_OR_SMS.matcher(query).find()) {
			return null;
		}
		return "UNLIMITED";
	}
}
