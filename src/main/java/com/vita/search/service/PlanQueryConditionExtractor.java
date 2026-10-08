package com.vita.search.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 질문 문장에서 요금제 조건(가격·데이터량·대상 그룹·데이터 무제한 여부·통화/문자 무제한·불가 여부)을 규칙 기반으로 뽑아낸다. LLM 호출 없이
 * 정규식만 쓰고, 상태가 없는 순수 함수라 DB 없이 단위 테스트가 된다.
 *
 * <p>왜 필요한가: 임베딩은 "3만1천원"과 설명문의 "31,000원"이 같은 값이라는 걸 모르고, "무제한 아니고"
 * 같은 부정도 거의 구분하지 못한다. 이런 정형 조건은 벡터 유사도 대신 plans 테이블의 컬럼과 직접
 * 비교하는 편이 정확하다.
 *
 * <p>오탐을 줄이기 위해 질문에 요금제를 가리키는 말({@code 요금제}, {@code 플랜}, 또는 "비타 라이트" 같은 요금제 이름)이 있을 때만 동작한다.
 * "로밍 5기가 얼마야?" 같은 FAQ 질문에서 데이터량 조건이 잘못 잡히는 것을 막기 위해서다.
 */
public final class PlanQueryConditionExtractor {

	/** 요금제를 가리키는 말. "비타"만 쓰면 "비타민"까지 걸리므로 실제 요금제 이름(비타 라이트 등)일 때만 인정한다. */
	private static final Pattern PLAN_CONTEXT = Pattern.compile(
			"요금제|플랜|비타\\s*(?:라이트|밸런스|플러스|맥스|유스|시니어|키즈|워치|태블릿)");

	/**
	 * 금액 바로 앞에 붙어 그 금액이 요금제 월 요금이 아님을 알려 주는 말(할인액·수수료·상품권 등). "수수료 3천원", "상품권 10만원".
	 * 금액 바로 앞(조사만 사이에 허용)에 올 때만 보므로 "할인 받은 뒤 3만원대"처럼 떨어져 있으면 영향이 없다.
	 */
	private static final Pattern FEE_NON_FILTER_BEFORE = Pattern.compile(
			"(?:수수료|위약금|연체료|연체|할인액|할인|캐시백|환급|적립|상품권|쿠폰|포인트|사은품|지원금|보조금|혜택|부가세|보증금|가입비|개통비|설치비|해지비"
					+ "|추가\\s*요금)\\s*(?:은|는|이|가|을|를|으로|로)?\\s*$");

	/**
	 * 금액 바로 뒤에 붙어 월 요금이 아님을 알려 주는 말. "3만원 할인 쿠폰", "10만원 상품권", "5천원 더 내요?"(차액). 뒤에 "이하/이상/대"가
	 * 오면 이 패턴에 걸리지 않아("3만원 이하 쿠폰 주는 요금제") 요금 조건으로 읽는다.
	 */
	private static final Pattern FEE_NON_FILTER_AFTER = Pattern.compile(
			"^\\s*(?:짜리|의)?\\s*(?:할인|깎|인하|감면|환급|캐시백|적립|페이백|쿠폰|상품권|포인트|사은품|기프티콘|혜택|지원금|보조금|선물|수수료|위약금|연체"
					+ "|부가세|보증금|가입비|개통비|설치비|해지비|(?:정도|쯤)?\\s*더\\s*(?:내|낸|나오|붙|받|드|들))");

	/** 데이터량 바로 앞에 붙어 요금제 기본 데이터가 아님을 알려 주는 말. "로밍으로 5기가", "추가 데이터 2기가", "쿠폰 2기가". */
	private static final Pattern DATA_NON_FILTER_BEFORE = Pattern.compile(
			"(?:로밍(?:으로|에서|할\\s*때|중에?|시)?|리필|쿠폰|선물|충전|추가(?:로|해서)?)\\s*(?:데이터)?\\s*$");

	/** 데이터량 바로 뒤에 붙어 기본 데이터가 아님을 알려 주는 말. "2기가 쿠폰", "5기가 선물". */
	private static final Pattern DATA_NON_FILTER_AFTER = Pattern.compile(
			"^\\s*(?:짜리|의)?\\s*(?:쿠폰|선물|리필|충전|추가|덤|보너스|이벤트)");

	/** 금액·데이터량 앞뒤에서 월 요금·기본 데이터가 아닌 용도(할인·쿠폰 등)를 알아볼 때 뒤쪽으로 볼 글자 수. */
	private static final int AMOUNT_CONTEXT_LOOKAHEAD = 12;

	/** "3만1천원", "3만원", "5천원"처럼 한글 단위가 섞인 금액. 그룹1=만 단위 수, 그룹2=천 단위 수. */
	private static final Pattern KOREAN_FEE = Pattern.compile("(?:(\\d+)\\s*만)?\\s*(?:(\\d+)\\s*천)?\\s*원");

	/** "31,000원", "31000원"처럼 숫자로만 쓴 금액. */
	private static final Pattern DIGIT_FEE = Pattern.compile("(\\d{1,3}(?:,\\d{3})+|\\d{4,6})\\s*원");

	/** "20기가", "20GB". 5G(네트워크)와 헷갈리지 않도록 단독 "G"는 받지 않고, 소수("1.5기가")는 제외한다. */
	private static final Pattern DATA_AMOUNT = Pattern.compile("(?<![\\d.])(\\d+)\\s*(?:기가바이트|기가|GB|gb|Gb)");

	/** 무제한 부정("무제한 아니고", "무제한은 빼고", "무제한 말고" 등). */
	private static final Pattern UNLIMITED_NEGATED = Pattern.compile(
			"무제한(?:은|이|를|요금제|\\s)*(?:아니|아닌|말고|빼고|제외|없)");

	/**
	 * 무제한을 뜻하는 표현. "마음껏/맘껏"은 "무제한"이라는 단어 없이 같은 뜻을 말하는 표현이고, "데이터 걱정 없이/없는"은 "데이터"를
	 * 이미 포함하고 있어 대상이 데이터로 정해진다.
	 */
	private static final Pattern UNLIMITED_EXPRESSION = Pattern.compile("무제한|마음껏|맘껏|데이터\\s*걱정\\s*없(?:이|는)");

	/**
	 * 무제한 표현이 가리킬 수 있는 대상 명사(데이터·통화·문자). 긴 표현을 앞에 둬서 "음성통화"가 "음성"으로 잘리지 않게 한다.
	 * "전화"는 "전화번호"처럼 조건이 아닌 쓰임이 많아서, 아래 명사 연쇄처럼 무제한 표현이 바로 붙은 경우에만 쓴다.
	 */
	private static final String POLICY_NOUN = "데이터|음성\\s*통화|문자\\s*메시지|통화|음성|문자|SMS|sms|전화";

	/**
	 * 문장 어딘가에서 끝나는 명사 연쇄("통화랑 문자", "통화도 데이터도", "통화, 문자", "통화 문자"). 무제한 표현이나 "안 되는" 바로
	 * 앞에 붙어 있는 것만 보려고 질문의 앞부분을 잘라 이 패턴을 끝에 맞춰 찾는다.
	 */
	private static final Pattern NOUN_CHAIN_BEFORE = Pattern.compile(
			"(?:(?:" + POLICY_NOUN + ")\\s*(?:도|은|는|이랑|이|가|을|를|랑|와|과|하고|및|,|·|/)?[\\s,]*)+$");

	private static final Pattern POLICY_NOUN_PATTERN = Pattern.compile(POLICY_NOUN);

	/** 무제한 표현 바로 뒤에 오는 명사("무제한 통화"). 앞에 명사가 없을 때만 쓴다. */
	private static final Pattern NOUN_RIGHT_AFTER = Pattern.compile("^\\s{0,2}(" + POLICY_NOUN + ")");

	/**
	 * 요금제를 꾸미는 "안 되는/없는" 표현("문자 안 되는 요금제"). 반드시 "요금제/플랜"으로 이어질 때만 인정한다.
	 * "요금제 바꿨는데 통화가 안 돼요"처럼 문장이 끝나는 장애 문의는 읽지 않는다(읽으면 워치·태블릿 요금제가 나오는 오탐이 된다).
	 */
	private static final Pattern ABSENT_MODIFIER = Pattern.compile("(?:안\\s*되는|안되는|없는|못\\s*하는)\\s*(?:요금제|플랜)");

	/** "음성통화 없이 데이터만 쓰는 요금제"처럼 통화가 없는 데이터 전용 요금제를 찾는 표현. */
	private static final Pattern VOICE_ABSENT_DATA_ONLY = Pattern.compile("(?:음성\\s*통화|통화|음성|전화)\\s*없이\\s*데이터만");

	/** "이하/이내/까지"에 더해 "안 넘는/못 넘는/넘지 않는"도 상한이다("넘는"만 보면 초과로 잘못 읽는다). */
	private static final Pattern BOUND_MAX = Pattern.compile(
			"^\\s*(?:이하|이내|아래|까지|안쪽|(?:안|못)\\s*넘|넘지\\s*(?:않|못))");
	/** "3만원 정도/쯤/안팎"처럼 대략적인 값. 정확히 그 값인 요금제가 없어도 근처 요금제를 찾도록 범위로 바꾼다. */
	private static final Pattern BOUND_APPROX = Pattern.compile("^\\s*(?:정도|쯤|안팎|내외|가량|언저리)");
	private static final Pattern BOUND_MAX_EXCLUSIVE = Pattern.compile("^\\s*미만");
	private static final Pattern BOUND_MIN = Pattern.compile("^\\s*(?:이상|부터)");
	private static final Pattern BOUND_MIN_EXCLUSIVE = Pattern.compile("^\\s*(?:초과|넘)");
	private static final Pattern BOUND_BAND = Pattern.compile("^\\s*대(?!신|해|비|체|여)");

	/** 금액/데이터 수치 바로 뒤에 붙는 비교 표현을 볼 범위(글자 수). */
	private static final int BOUND_LOOKAHEAD = 8;

	private static final int MB_PER_GB = 1024;
	private static final int FEE_BAND_WIDTH = 9_999;
	/** "정도/쯤"을 범위로 바꿀 때의 허용 오차. 요금은 ±10%(3만원 → 2.7만~3.3만), 데이터는 ±25%(20GB → 15~25GB). */
	private static final int FEE_APPROX_PERCENT = 10;
	private static final int DATA_APPROX_PERCENT = 25;

	/**
	 * "요금제"를 잘못 적은 표기("요금재", "요근제"). 사전에 없는 말이라 다른 단어와 헷갈릴 일이 없어 그대로 바꾼다. 오표기가 있으면
	 * 요금제 질문으로 인식하지 못해 조건 추출이 아예 켜지지 않기 때문에, 조건을 읽기 전에 먼저 바른 표기로 고친다.
	 */
	private static final Pattern PLAN_WORD_TYPO = Pattern.compile("요금재|요근제");

	/**
	 * "요금지"(요금제의 오표기). "요금지급"처럼 실제 단어의 앞부분일 수 있어서, 뒤에 조사가 붙거나 단어가 끝날 때만 오표기로 본다.
	 */
	private static final Pattern PLAN_WORD_TYPO_GUARDED = Pattern.compile("요금지(?=$|[^가-힣]|[는은이가을를도만에와과로의])");

	/** "무제한"을 잘못 적은 표기("무재한"). */
	private static final Pattern UNLIMITED_TYPO = Pattern.compile("무재한");

	/** 대상 그룹(plans.target_group 값) 하나를 가리키는 말들. */
	private record GroupWord(String group, Pattern pattern) {
	}

	/**
	 * 대상 그룹을 가리키는 말. 오탐을 막으려고 뒤에 오는 글자나 문맥을 함께 본다.
	 * <ul>
	 *   <li>청년: 연령대("20대")와 대학생·사회초년생도 청년 요금제(만 19~34세) 대상이다.</li>
	 *   <li>시니어: "부모님"은 뒤에 동의·허락·명의 같은 말이 오면 어르신이 아니라 가입 절차 이야기("부모님 동의 받아야 돼요")라 뺀다.</li>
	 *   <li>키즈: 초·중·고등학생(만 18세 이하)과 어린이를 뜻하는 말. "아이"는 "아이디", "아이돌", "아이폰"처럼 다른 단어의 앞부분일 수
	 *       있어서, 단독으로 쓰이거나 조사가 붙을 때만 인정한다. "아기자기"의 "아기"도 뺀다.</li>
	 *   <li>태블릿: "아이패드", "갤럭시 탭"도 태블릿이다.</li>
	 *   <li>일반: "일반적인", "일반 전화/문자/통화"의 "일반"은 대상 그룹 표현이 아니다.</li>
	 * </ul>
	 */
	private static final List<GroupWord> TARGET_GROUP_WORDS = List.of(
			new GroupWord("YOUTH", Pattern.compile("청년|유스|(?<![\\d가-힣])20대|대학생|대학원생|사회초년생")),
			new GroupWord("SENIOR", Pattern.compile(
					"시니어|어르신|할머니|할아버지|노인|실버|고령|(?<![\\d가-힣])[678]0대|부모님(?!\\s*(?:동의|허락|승인|명의|서류|확인|인증))")),
			new GroupWord("KIDS", Pattern.compile(
					"키즈|어린이|초등학생|중학생|고등학생|초딩|중딩|고딩|애들|아기(?!자기)|유아"
							+ "|(?<![가-힣])아이(?=$|[^가-힣]|(?:가|는|은|도|를|을|랑|와|과|한테|에게|의|들|용|께서?)+(?![가-힣]))")),
			new GroupWord("WATCH", Pattern.compile("워치")),
			new GroupWord("TABLET", Pattern.compile("태블릿|아이패드|갤럭시\\s*탭")),
			new GroupWord("GENERAL", Pattern.compile("일반(?!적|\\s*(?:전화|통화|문자|우편|택배|상담))")));

	/** 대상 그룹 말이 나온 자리. */
	private record GroupMention(String group, int start, int end) {
	}

	/** 대상 그룹 말 바로 뒤에서 그 그룹을 제외한다는 뜻을 알려 주는 표현("시니어 말고", "청년 아닌", "시니어 요금제 말고", "워치 빼고"). */
	private static final Pattern GROUP_NEGATION_AFTER = Pattern.compile(
			"^(?:\\s*(?:요금제|플랜|용|전용|쪽|사용자|이용자|고객|사람|분))*\\s*(?:은|는|이|가|을|를|도)?\\s*(?:말고|아닌|아니고|아니라|빼고|제외|외에|이외|외의)");

	/** 대상 그룹 말 사이를 잇는 말("워치나 태블릿 말고"에서 "나"). 제외 표현이 이어진 말들에 함께 걸리게 한다. */
	private static final Pattern GROUP_CONNECTOR = Pattern.compile("^\\s*(?:이나|나|이랑|랑|이고|하고|와|과|또는|혹은|,|·|/)?\\s*$");

	/** 폰과 기기(워치·태블릿)를 함께 쓰는 요금제를 찾는 질문을 알아보는 말. 이때는 기기 전용 요금제가 아니라 같이 쓰는 요금제를 뜻한다. */
	private static final Pattern PHONE_WORD = Pattern.compile("폰|휴대전화");

	private static final Pattern SHARED_USE_WORD = Pattern.compile("같이|함께|나눠|나누어|공유|쉐어|셰어|묶어");

	/** 워치·태블릿 같은 기기 전용 요금제를 가리키는 말. 요금제 단어 없이도("스마트워치 데이터 얼마나 줘?") 쓰인다. */
	private static final Pattern DEVICE_PLAN_MENTION = Pattern.compile("워치|태블릿|패드|갤럭시 ?탭");

	private PlanQueryConditionExtractor() {
	}

	/** 수치 뒤 비교 표현의 종류. */
	private enum Bound { EXACT, MAX, MAX_EXCLUSIVE, MIN, MIN_EXCLUSIVE, BAND, APPROX }

	/** 하나의 수치 조건을 구간으로 바꾼 값(양 끝 포함, null이면 그쪽은 제한 없음). */
	private record Range(Long min, Long max) {
	}

	/**
	 * 질문이 워치·태블릿 같은 기기 전용 요금제를 언급하는지. 요금제 단어가 없어도("스마트워치 데이터 얼마나 줘?")
	 * 판단한다. 언급이 없으면 기기 전용 요금제는 기본 검색 결과에서 뺀다.
	 */
	public static boolean mentionsDevicePlan(String query) {
		return query != null && DEVICE_PLAN_MENTION.matcher(query).find();
	}

	/**
	 * 질문에서 요금제 조건을 뽑는다. "요금재", "무재한"처럼 핵심 단어의 흔한 오표기는 먼저 바른 표기로 고친 뒤 읽는다.
	 *
	 * @param rawQuery 사용자가 입력한 질문(또는 질문 변환 결과)
	 * @return 추출된 조건. 요금제 관련 질문이 아니거나 조건이 없으면 {@link PlanQueryConditions#isEmpty()}인 값
	 */
	public static PlanQueryConditions extract(String rawQuery) {
		if (rawQuery == null) {
			return new PlanQueryConditions(null, null, null, null, null, null);
		}
		String query = fixSpelling(rawQuery);
		if (!PLAN_CONTEXT.matcher(query).find()) {
			return new PlanQueryConditions(null, null, null, null, null, null);
		}

		Range fee = extractFee(query);
		Range data = extractData(query);
		Policies policies = extractPolicies(query);

		return new PlanQueryConditions(
				fee == null || fee.min() == null ? null : fee.min().intValue(),
				fee == null || fee.max() == null ? null : fee.max().intValue(),
				data == null ? null : data.min(),
				data == null ? null : data.max(),
				extractTargetGroup(query),
				policies.data(),
				policies.voice(),
				policies.sms());
	}

	/**
	 * 조건을 읽는 데 쓰는 핵심 단어("요금제", "무제한")의 흔한 오표기를 바른 표기로 고친다. 조건 값(금액·데이터량)은 건드리지 않고
	 * 다른 말과 겹칠 수 있는 표기는 넣지 않는다.
	 */
	private static String fixSpelling(String query) {
		String fixed = PLAN_WORD_TYPO.matcher(query).replaceAll("요금제");
		fixed = PLAN_WORD_TYPO_GUARDED.matcher(fixed).replaceAll("요금제");
		return UNLIMITED_TYPO.matcher(fixed).replaceAll("무제한");
	}

	/** 금액이 정확히 하나일 때만 조건으로 삼는다(두 개 이상이면 "3만원에서 5만원 사이"처럼 해석이 모호). */
	private static Range extractFee(String query) {
		Long amount = null;
		int end = -1;
		int count = 0;

		Matcher digit = DIGIT_FEE.matcher(query);
		while (digit.find()) {
			// 할인액·수수료·상품권처럼 월 요금이 아닌 금액은 세지 않는다("3만원 할인 쿠폰 주는 요금제"에서 3만원은 요금이 아니다).
			if (isNonFilterAmount(query, digit.start(), digit.end(), FEE_NON_FILTER_BEFORE, FEE_NON_FILTER_AFTER)) {
				continue;
			}
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
			if (isNonFilterAmount(query, korean.start(), korean.end(), FEE_NON_FILTER_BEFORE, FEE_NON_FILTER_AFTER)) {
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

	/** 금액·데이터량(start~end) 바로 앞이나 뒤에 월 요금·기본 데이터가 아님을 알려 주는 말이 있는지. */
	private static boolean isNonFilterAmount(String query, int start, int end, Pattern before, Pattern after) {
		String head = query.substring(0, start);
		String tail = query.substring(end, Math.min(query.length(), end + AMOUNT_CONTEXT_LOOKAHEAD));
		return before.matcher(head).find() || after.matcher(tail).find();
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
			// 로밍·쿠폰·추가 데이터처럼 요금제 기본 데이터가 아닌 데이터량은 세지 않는다.
			if (isNonFilterAmount(query, matcher.start(), matcher.end(), DATA_NON_FILTER_BEFORE, DATA_NON_FILTER_AFTER)) {
				continue;
			}
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
		if (BOUND_APPROX.matcher(tail).find()) {
			return Bound.APPROX;
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
			case APPROX -> {
				long tolerance = amount * (isFee ? FEE_APPROX_PERCENT : DATA_APPROX_PERCENT) / 100;
				yield new Range(amount - tolerance, amount + tolerance);
			}
		};
	}

	/**
	 * 질문이 가리키는 대상 그룹(plans.target_group 값)을 찾는다. {@link #extract}와 달리 "요금제" 같은 말이
	 * 없어도 동작한다 — 극값 조회처럼 이미 요금제 질문이라고 확정된 뒤에 호출하는 용도다.
	 *
	 * @return GENERAL/YOUTH/SENIOR/KIDS/WATCH/TABLET 중 하나. 대상 표현이 없거나 여러 그룹이 섞이면 null
	 */
	public static String targetGroupOf(String query) {
		return query == null ? null : extractTargetGroup(query);
	}

	/**
	 * 대상 그룹 말이 정확히 한 그룹만 가리킬 때만 채택한다(여러 그룹이 섞이면 null).
	 *
	 * <p>"시니어 말고", "청년 아닌"처럼 제외하는 말은 그 그룹으로 세지 않는다. "청년 말고 일반 요금제"는 일반 하나만 남아 일반으로 읽고,
	 * "시니어 말고 3만원대 요금제"는 남는 그룹이 없어 대상 조건이 없다("워치나 태블릿 말고"처럼 제외 표현이 이어진 말들에 같이 걸린다).
	 * 또 "폰이랑 태블릿 데이터 같이 쓰는 요금제"는 태블릿 전용 요금제를 찾는 질문이 아니라서 기기 그룹(워치·태블릿)을 대상으로 읽지 않는다.
	 */
	private static String extractTargetGroup(String query) {
		List<GroupMention> mentions = new ArrayList<>();
		for (GroupWord word : TARGET_GROUP_WORDS) {
			Matcher matcher = word.pattern().matcher(query);
			while (matcher.find()) {
				mentions.add(new GroupMention(word.group(), matcher.start(), matcher.end()));
			}
		}
		mentions.sort(Comparator.comparingInt(GroupMention::start));

		// 뒤에서부터 보며 제외 표현이 걸린 말을 찾는다. 바로 뒤에 제외 표현이 있거나, 이어진 다음 말이 제외되면 함께 제외된다.
		boolean[] negated = new boolean[mentions.size()];
		for (int i = mentions.size() - 1; i >= 0; i--) {
			GroupMention current = mentions.get(i);
			String tail = query.substring(current.end(), Math.min(query.length(), current.end() + 16));
			boolean direct = GROUP_NEGATION_AFTER.matcher(tail).find();
			boolean chained = false;
			if (i + 1 < mentions.size() && negated[i + 1]) {
				GroupMention next = mentions.get(i + 1);
				chained = next.start() >= current.end()
						&& GROUP_CONNECTOR.matcher(query.substring(current.end(), next.start())).matches();
			}
			negated[i] = direct || chained;
		}

		Set<String> groups = new LinkedHashSet<>();
		for (int i = 0; i < mentions.size(); i++) {
			if (!negated[i]) {
				groups.add(mentions.get(i).group());
			}
		}
		if (SHARED_USE_WORD.matcher(query).find() && PHONE_WORD.matcher(query).find()) {
			groups.remove("WATCH");
			groups.remove("TABLET");
		}
		return groups.size() == 1 ? groups.iterator().next() : null;
	}

	/** 무제한·불가 표현이 가리키는 대상. */
	private enum PolicyTarget { DATA, VOICE, SMS }

	/** 데이터·통화·문자 정책 조건. 읽지 못한 항목은 null. */
	private record Policies(String data, String voice, String sms) {
	}

	/**
	 * 데이터·통화·문자 정책을 읽는다.
	 *
	 * <p>"무제한"(또는 "마음껏")이 무엇에 붙는지는 표현 바로 앞의 명사 연쇄로 정한다("통화랑 문자 무제한"은 통화·문자, "통화도 데이터도
	 * 무제한"은 통화·데이터). 앞에 명사가 없으면 뒤의 명사("무제한 통화")를 보고, 그것도 없으면 데이터로 본다("무제한 요금제"는 데이터
	 * 무제한). 예전에는 질문에 통화·문자 단어가 있으면 데이터 무제한을 통째로 포기했는데, 이제는 무제한 표현마다 대상을 따로 정한다.
	 *
	 * <p>"무제한 아니고/빼고/말고"는 데이터면 LIMITED다. 통화·문자의 부정("통화 무제한 아닌")은 값이 LIMITED와 NONE을 함께 뜻해서 읽지
	 * 않는다. 통화·문자가 안 되는 요금제를 찾는 질문("문자 안 되는 요금제", "음성통화 없이 데이터만")은 NONE으로 읽는다.
	 */
	private static Policies extractPolicies(String query) {
		String data = null;
		String voice = null;
		String sms = null;

		Matcher expression = UNLIMITED_EXPRESSION.matcher(query);
		while (expression.find()) {
			boolean negated = expression.group().equals("무제한")
					&& UNLIMITED_NEGATED.matcher(query).region(expression.start(), query.length()).lookingAt();
			for (PolicyTarget target : unlimitedTargets(query, expression)) {
				switch (target) {
					case DATA -> data = negated || "LIMITED".equals(data) ? "LIMITED" : "UNLIMITED";
					case VOICE -> voice = negated ? voice : "UNLIMITED";
					case SMS -> sms = negated ? sms : "UNLIMITED";
				}
			}
		}

		Matcher absent = ABSENT_MODIFIER.matcher(query);
		while (absent.find()) {
			for (PolicyTarget target : nounChainBefore(query.substring(0, absent.start()))) {
				if (target == PolicyTarget.VOICE && voice == null) {
					voice = "NONE";
				} else if (target == PolicyTarget.SMS && sms == null) {
					sms = "NONE";
				}
			}
		}
		if (voice == null && VOICE_ABSENT_DATA_ONLY.matcher(query).find()) {
			voice = "NONE";
		}
		return new Policies(data, voice, sms);
	}

	/** 무제한 표현 하나가 가리키는 대상들. 앞의 명사 연쇄 → 뒤의 명사 → 데이터 순으로 정한다. */
	private static Set<PolicyTarget> unlimitedTargets(String query, Matcher expression) {
		if (expression.group().startsWith("데이터")) {
			return EnumSet.of(PolicyTarget.DATA);
		}
		Set<PolicyTarget> before = nounChainBefore(query.substring(0, expression.start()));
		if (!before.isEmpty()) {
			return before;
		}
		String tail = query.substring(expression.end(), Math.min(query.length(), expression.end() + 6));
		Matcher after = NOUN_RIGHT_AFTER.matcher(tail);
		if (after.find()) {
			return EnumSet.of(targetOf(after.group(1)));
		}
		return EnumSet.of(PolicyTarget.DATA);
	}

	/** 주어진 앞부분의 끝에 이어진 명사 연쇄가 가리키는 대상들. 연쇄가 없으면 빈 집합. */
	private static Set<PolicyTarget> nounChainBefore(String prefix) {
		Set<PolicyTarget> targets = EnumSet.noneOf(PolicyTarget.class);
		Matcher chain = NOUN_CHAIN_BEFORE.matcher(prefix);
		if (!chain.find()) {
			return targets;
		}
		Matcher noun = POLICY_NOUN_PATTERN.matcher(chain.group());
		while (noun.find()) {
			targets.add(targetOf(noun.group()));
		}
		return targets;
	}

	private static PolicyTarget targetOf(String noun) {
		String compact = noun.replaceAll("\\s+", "");
		if (compact.equals("데이터")) {
			return PolicyTarget.DATA;
		}
		if (compact.startsWith("문자") || compact.equalsIgnoreCase("SMS")) {
			return PolicyTarget.SMS;
		}
		return PolicyTarget.VOICE;
	}
}
