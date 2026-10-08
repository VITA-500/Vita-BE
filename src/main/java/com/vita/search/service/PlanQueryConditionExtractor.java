package com.vita.search.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
	private static final Pattern DIGIT_FEE = Pattern.compile("(?<![\\d,])(\\d{1,3}(?:,\\d{3})+|\\d{4,6})\\s*원");

	/** "20기가", "20GB". 5G(네트워크)와 헷갈리지 않도록 단독 "G"는 받지 않고, 소수("1.5기가")는 제외한다. */
	private static final Pattern DATA_GB = Pattern.compile("(?<![\\d.])(\\d+(?:\\.\\d+)?)\\s*(?:기가바이트|기가|GB|gb|Gb)");

	/** "500MB", "500메가". 요금제 기본 데이터는 보통 GB 단위지만 작은 데이터량도 MB로 말한다. */
	private static final Pattern DATA_MB = Pattern.compile("(?<![\\d.])(\\d+)\\s*(?:메가바이트|메가|MB|mb|Mb)");

	/**
	 * "20G"처럼 G만 쓴 데이터량. 5G·4G·3G는 이동통신 세대라서 {@link #MAX_NETWORK_GENERATION} 이하는 데이터량으로 읽지 않는다.
	 * 뒤에 알파벳이 붙으면("20GB", "10Gbps") 다른 단위라 제외한다.
	 */
	private static final Pattern DATA_G_ONLY = Pattern.compile("(?<![\\d.A-Za-z])(\\d+)\\s*G(?![A-Za-z])");

	private static final int MAX_NETWORK_GENERATION = 5;

	/**
	 * 두 금액·데이터량을 범위로 잇는 말("3만원에서 5만원 사이", "3만원부터 5만원까지", "3만원~5만원"). 두 수치 사이에 이 말만 있을 때 범위로 읽는다.
	 */
	private static final Pattern RANGE_CONNECTOR = Pattern.compile("\\s*(?:에서부터|에서|부터|~|-|–)\\s*");

	/**
	 * "삼만오천원", "만오천원", "이십기가", "백건"처럼 한글 숫자로 쓴 금액·데이터량·통화 분·문자 건수. 십·백·천·만 중 하나를 포함하고 바로 뒤에 원·기가·메가·분·건 같은 단위가 올 때만
	 * 숫자로 바꾼다. "수천원", "몇만원"처럼 앞에 한글이 붙은 말은 정확한 값이 아니라서 바꾸지 않는다.
	 */
	private static final Pattern KOREAN_NUMERAL_BEFORE_UNIT = Pattern.compile(
			"(?<![가-힣\\d.])((?:[일이삼사오육칠팔구]?[십백천만])+[일이삼사오육칠팔구]?)(?=\\s*(?:원|기가바이트|기가|GB|gb|Gb|메가바이트|메가|MB|mb|Mb|분|건|개))");

	/** "3.5만원"처럼 소수에 만 단위가 붙은 금액. 그대로 두면 소수점 뒤의 "5만원"만 읽혀 5만원으로 잘못 읽는다. */
	private static final Pattern DECIMAL_MAN = Pattern.compile("(?<![\\d.])(\\d+)\\.(\\d+)\\s*만(?=\\s*원)");

	/** "100분", "300 분". 앞에 통화 말이 붙은 것만 통화 제공량으로 읽는다({@link #VOICE_NOUN_BEFORE}). */
	private static final Pattern VOICE_MINUTES = Pattern.compile("(?<![\\d.])(\\d+)\\s*분(?!\\s*(?:만에|째|동안|뒤|후|전(?!화)|마다|간격))");

	/** "100건", "100개", "100통". 앞에 문자 말이 붙은 것만 문자 제공량으로 읽는다({@link #SMS_NOUN_BEFORE}). */
	private static final Pattern SMS_COUNT = Pattern.compile("(?<![\\d.])(\\d+)\\s*(?:건|개(?!월)|통)");

	/**
	 * 통화 분 바로 앞에 붙는 통화 말. "통화 100분", "통화는 한 달에 100분", "음성통화 약 300분". 사이에 조사와 "한 달에" 같은 기간 표현만 허용해
	 * "통화 무제한이고 데이터 5기가, 분..."처럼 멀리 떨어진 수치를 통화 분으로 읽지 않는다.
	 */
	private static final Pattern VOICE_NOUN_BEFORE = Pattern.compile(
			"(?:음성\s*통화|통화|음성|전화)\s*(?:은|는|이|가|을|를|도|량|시간|제공량)?\s*(?:(?:월|한\s*달|매월|매달)\s*(?:에는|에)?\s*)?(?:약|총|최소|최대)?\s*$");

	/** 통화 분 바로 뒤에 붙는 통화 말. "100분 통화", "100분 주는 통화". */
	private static final Pattern VOICE_NOUN_AFTER = Pattern.compile(
			"^\s*(?:이상|이하|이내|미만|초과|정도|쯤)?\s*(?:짜리|주는|제공하는|되는|있는)?\s*(?:음성\s*통화|통화|전화)");

	/** 문자 건수 바로 앞에 붙는 문자 말. */
	private static final Pattern SMS_NOUN_BEFORE = Pattern.compile(
			"(?:문자\s*메시지|문자|SMS|sms|메시지)\s*(?:은|는|이|가|을|를|도|량|제공량)?\s*(?:(?:월|한\s*달|매월|매달)\s*(?:에는|에)?\s*)?(?:약|총|최소|최대)?\s*$");

	/** 문자 건수 바로 뒤에 붙는 문자 말. */
	private static final Pattern SMS_NOUN_AFTER = Pattern.compile(
			"^\s*(?:이상|이하|이내|미만|초과|정도|쯤)?\s*(?:짜리|주는|제공하는|되는|있는)?\s*(?:문자\s*메시지|문자|SMS|sms|메시지)");

	/** 통화 분·문자 건수 수치 둘 사이에 범위를 잇는 말만 있는지("100분에서 300분", "100분 이상 300분"). */
	private static final Pattern USAGE_RANGE_GAP = Pattern.compile(
			"\\s*(?:이상|이하|이내|초과|미만)?\\s*(?:에서부터|에서|부터|~|-|–)?\\s*");

	/**
	 * 쓰는 양을 말하는 표현("통화 300분 쓰는데", "문자 200건 사용해요"). 필요한 만큼 이상을 제공하는 요금제를 찾는 뜻이라서 정확 일치가 아니라
	 * 하한으로 읽는다. 요금제가 "주는/제공하는" 양을 말하는 표현은 정확 일치로 읽는다.
	 */
	private static final Pattern USAGE_VERB_AFTER = Pattern.compile(
			"^\s*(?:정도|쯤|가량|씩)?\s*(?:을|를)?\s*(?:쓰|써|쓴|사용)");

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
			"^\\s*(?:이하|이내|아래|까지|안쪽|안으로|안에|밑|(?:안|못)\\s*넘|넘지\\s*(?:않|못)"
					+ "|넘는\\s*(?:건|게|거|것)\\s*(?:은\\s*)?(?:좀\\s*)?(?:부담|싫|별로|곤란|무리|비싸))");
	/** "3만원 정도/쯤/안팎"처럼 대략적인 값. 정확히 그 값인 요금제가 없어도 근처 요금제를 찾도록 범위로 바꾼다. */
	private static final Pattern BOUND_APPROX = Pattern.compile("^\\s*(?:정도|쯤|안팎|내외|가량|언저리|근처|근방|전후)");
	/** "미만", "안 되는", "보다 싼/저렴한/적은": 그 값보다 작다. */
	private static final Pattern BOUND_MAX_EXCLUSIVE = Pattern.compile(
			"^\\s*(?:미만|안\\s*되는|안되는|보다\\s*(?:더\\s*)?(?:싼|싸|저렴|낮|적|작))");
	private static final Pattern BOUND_MIN = Pattern.compile("^\\s*(?:이상|부터)");
	/** "초과", "넘는", "보다 비싼/높은/많은": 그 값보다 크다. */
	private static final Pattern BOUND_MIN_EXCLUSIVE = Pattern.compile(
			"^\\s*(?:초과|넘|보다\\s*(?:더\\s*)?(?:비싼|비싸|높|많|큰|크))");
	private static final Pattern BOUND_BAND = Pattern.compile("^\\s*대(?!신|해|비|체|여)");

	/** 금액/데이터 수치 바로 뒤에 붙는 비교 표현을 볼 범위(글자 수). */
	private static final int BOUND_LOOKAHEAD = 14;

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
		if (query == null) {
			return false;
		}
		GroupScan scan = scanGroups(query);
		Matcher device = DEVICE_PLAN_MENTION.matcher(query);
		while (device.find()) {
			// "워치 말고 폰 요금제"의 워치는 기기 요금제를 찾는다는 뜻이 아니라 빼 달라는 뜻이다.
			if (!scan.negatesDeviceAt(device.start())) {
				return true;
			}
		}
		return false;
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
		String query = normalizeNumbers(fixSpelling(rawQuery));
		if (!PLAN_CONTEXT.matcher(query).find()) {
			return new PlanQueryConditions(null, null, null, null, null, null);
		}

		Range fee = extractFee(query);
		Range data = extractData(query);
		Policies policies = extractPolicies(query);
		GroupRead groups = readTargetGroups(query);
		Range voiceMinutes = extractUsage(query, VOICE_MINUTES, VOICE_NOUN_BEFORE, VOICE_NOUN_AFTER);
		Range smsCount = extractUsage(query, SMS_COUNT, SMS_NOUN_BEFORE, SMS_NOUN_AFTER);

		return new PlanQueryConditions(
				fee == null || fee.min() == null ? null : fee.min().intValue(),
				fee == null || fee.max() == null ? null : fee.max().intValue(),
				data == null ? null : data.min(),
				data == null ? null : data.max(),
				groups.group(),
				policies.data(),
				policies.voice(),
				policies.sms(),
				groups.excluded(),
				toInt(voiceMinutes == null ? null : voiceMinutes.min()),
				toInt(voiceMinutes == null ? null : voiceMinutes.max()),
				toInt(smsCount == null ? null : smsCount.min()),
				toInt(smsCount == null ? null : smsCount.max()));
	}

	private static Integer toInt(Long value) {
		return value == null ? null : Math.toIntExact(value);
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

	/** 금액·데이터량 수치 하나와 그 자리. 값은 금액이면 원, 데이터량이면 MB. */
	private record AmountToken(long value, int start, int end) {
	}

	/**
	 * 월 요금 조건을 읽는다. 금액이 하나면 뒤의 비교 표현으로 구간을 정하고("3만원 이하"), 둘이면 범위로 읽는다("3만원에서 5만원 사이",
	 * "3만원 이상 5만원 이하"). 셋 이상이거나 범위로 읽을 수 없으면 해석이 모호해 읽지 않는다.
	 */
	private static Range extractFee(String query) {
		List<AmountToken> tokens = new ArrayList<>();

		Matcher digit = DIGIT_FEE.matcher(query);
		while (digit.find()) {
			// 할인액·수수료·상품권처럼 월 요금이 아닌 금액은 세지 않는다("3만원 할인 쿠폰 주는 요금제"에서 3만원은 요금이 아니다).
			if (isNonFilterAmount(query, digit.start(), digit.end(), FEE_NON_FILTER_BEFORE, FEE_NON_FILTER_AFTER)) {
				continue;
			}
			tokens.add(new AmountToken(Long.parseLong(digit.group(1).replace(",", "")), digit.start(), digit.end()));
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
			long man = korean.group(1) == null ? 0 : Long.parseLong(korean.group(1));
			long cheon = korean.group(2) == null ? 0 : Long.parseLong(korean.group(2));
			tokens.add(new AmountToken(man * 10_000 + cheon * 1_000, korean.start(), korean.end()));
		}
		return rangeOf(query, tokens, true);
	}

	/**
	 * 수치가 하나면 뒤의 비교 표현으로, 둘이면 범위로 구간을 정한다. 수치가 없거나 셋 이상이거나 범위로 읽을 수 없으면 null.
	 *
	 * @param isFee 금액이면 true(데이터량이면 false). "3만원대"는 금액에서만 의미가 있다.
	 */
	private static Range rangeOf(String query, List<AmountToken> found, boolean isFee) {
		List<AmountToken> tokens = new ArrayList<>(found);
		tokens.sort(Comparator.comparingInt(AmountToken::start));
		if (tokens.size() == 1) {
			AmountToken token = tokens.get(0);
			return toRange(token.value(), boundAfter(query, token.end()), isFee);
		}
		if (tokens.size() == 2) {
			return combineTwo(query, tokens.get(0), tokens.get(1), isFee);
		}
		return null;
	}

	/**
	 * 두 수치를 범위로 읽는다. 사이에 "에서/부터/~"만 있으면 작은 쪽부터 큰 쪽까지, 앞쪽이 "이상"이고 뒤쪽이 "이하"(또는 그 반대)이면 그 하한과
	 * 상한을 잇는다. 그 밖의 조합("3만원 이하 5만원 이하")은 무엇을 뜻하는지 알 수 없어 null이다.
	 */
	private static Range combineTwo(String query, AmountToken first, AmountToken second, boolean isFee) {
		String between = query.substring(first.end(), Math.max(first.end(), second.start()));
		if (RANGE_CONNECTOR.matcher(between).matches()) {
			return new Range(Math.min(first.value(), second.value()), Math.max(first.value(), second.value()));
		}
		Range a = toRange(first.value(), boundAfter(query, first.end()), isFee);
		Range b = toRange(second.value(), boundAfter(query, second.end()), isFee);
		if (a.min() != null && a.max() == null && b.min() == null && b.max() != null && a.min() <= b.max()) {
			return new Range(a.min(), b.max());
		}
		if (b.min() != null && b.max() == null && a.min() == null && a.max() != null && b.min() <= a.max()) {
			return new Range(b.min(), a.max());
		}
		return null;
	}

	/**
	 * 한글 숫자와 소수 표기를 검색 조건으로 읽을 수 있는 숫자로 바꾼다. "삼만오천원"은 35000원, "이십기가"는 20기가, "3.5만원"은 35000원이 된다.
	 * 바꾸지 않으면 "삼만오천원"은 읽히지 않고, "3.5만원"은 소수점 뒤의 5만원만 읽힌다.
	 */
	private static String normalizeNumbers(String query) {
		Matcher numeral = KOREAN_NUMERAL_BEFORE_UNIT.matcher(query);
		StringBuilder converted = new StringBuilder();
		while (numeral.find()) {
			numeral.appendReplacement(converted, Long.toString(koreanNumeralValue(numeral.group(1))));
		}
		numeral.appendTail(converted);

		Matcher decimal = DECIMAL_MAN.matcher(converted);
		StringBuilder result = new StringBuilder();
		while (decimal.find()) {
			long won = new BigDecimal(decimal.group(1) + "." + decimal.group(2))
					.multiply(BigDecimal.valueOf(10_000)).setScale(0, RoundingMode.HALF_UP).longValue();
			decimal.appendReplacement(result, Long.toString(won));
		}
		decimal.appendTail(result);
		return result.toString();
	}

	/** "삼만오천"(35000), "만오천"(15000), "이십"(20)처럼 십·백·천·만으로 쓴 한글 숫자의 값. */
	private static long koreanNumeralValue(String numeral) {
		long total = 0;
		long section = 0;
		long digit = 0;
		for (char c : numeral.toCharArray()) {
			int value = "일이삼사오육칠팔구".indexOf(c) + 1;
			if (value > 0) {
				digit = value;
				continue;
			}
			switch (c) {
				case '십' -> section += (digit == 0 ? 1 : digit) * 10;
				case '백' -> section += (digit == 0 ? 1 : digit) * 100;
				case '천' -> section += (digit == 0 ? 1 : digit) * 1_000;
				case '만' -> {
					long part = section + digit;
					total += (part == 0 ? 1 : part) * 10_000;
					section = 0;
				}
				default -> { }
			}
			digit = 0;
		}
		return total + section + digit;
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

	/**
	 * 통화 분·문자 건수 조건을 읽는다. 수치 뒤에 단위("분", "건")가 붙고 그 앞이나 뒤에 통화·문자 말이 붙은 것만 센다. 수치가 하나면 비교
	 * 표현으로 구간을 정하고("통화 300분 이상"), 둘이면 범위로 읽는다. 쓰는 양을 말하는 표현("300분 쓰는데")은 비교 표현이 없어도 하한이다.
	 *
	 * @param amount     수치+단위 패턴(그룹1=수치)
	 * @param nounBefore 수치 바로 앞에 와야 하는 통화·문자 말
	 * @param nounAfter  수치 바로 뒤에 올 수 있는 통화·문자 말(앞에 없을 때)
	 */
	private static Range extractUsage(String query, Pattern amount, Pattern nounBefore, Pattern nounAfter) {
		List<AmountToken> tokens = new ArrayList<>();
		Matcher matcher = amount.matcher(query);
		while (matcher.find()) {
			String head = query.substring(0, matcher.start());
			String tail = query.substring(matcher.end(), Math.min(query.length(), matcher.end() + BOUND_LOOKAHEAD));
			// "통화 100분에서 300분 사이"의 300분처럼 통화·문자 말 없이 앞의 수치와 이어진 수치도 같은 항목의 범위 끝으로 센다.
			boolean continuesRange = !tokens.isEmpty()
					&& USAGE_RANGE_GAP.matcher(query.substring(tokens.get(tokens.size() - 1).end(), matcher.start())).matches();
			if (nounBefore.matcher(head).find() || nounAfter.matcher(tail).find() || continuesRange) {
				tokens.add(new AmountToken(Long.parseLong(matcher.group(1)), matcher.start(), matcher.end()));
			}
		}
		if (tokens.size() == 1) {
			AmountToken token = tokens.get(0);
			String tail = query.substring(token.end(), Math.min(query.length(), token.end() + BOUND_LOOKAHEAD));
			Bound bound = boundAfter(query, token.end());
			if (bound == Bound.EXACT && USAGE_VERB_AFTER.matcher(tail).find()) {
				bound = Bound.MIN;
			}
			return toRange(token.value(), bound, false);
		}
		return rangeOf(query, tokens, false);
	}

	/**
	 * 기본 데이터량 조건을 읽는다(1GB = 1024MB, plans.base_data_mb 기준). "20기가", "20GB", "20G", "1.5기가", "500MB"를 읽고, 수치가 둘이면
	 * 금액과 같이 범위로 읽는다("20기가에서 30기가 사이"). 로밍·쿠폰·추가 데이터처럼 기본 데이터가 아닌 수치는 세지 않는다.
	 */
	private static Range extractData(String query) {
		List<AmountToken> tokens = new ArrayList<>();

		Matcher gb = DATA_GB.matcher(query);
		while (gb.find()) {
			if (isNonFilterAmount(query, gb.start(), gb.end(), DATA_NON_FILTER_BEFORE, DATA_NON_FILTER_AFTER)) {
				continue;
			}
			long mb = new BigDecimal(gb.group(1)).multiply(BigDecimal.valueOf(MB_PER_GB)).setScale(0, RoundingMode.HALF_UP).longValue();
			tokens.add(new AmountToken(mb, gb.start(), gb.end()));
		}

		Matcher mb = DATA_MB.matcher(query);
		while (mb.find()) {
			if (isNonFilterAmount(query, mb.start(), mb.end(), DATA_NON_FILTER_BEFORE, DATA_NON_FILTER_AFTER)) {
				continue;
			}
			tokens.add(new AmountToken(Long.parseLong(mb.group(1)), mb.start(), mb.end()));
		}

		Matcher g = DATA_G_ONLY.matcher(query);
		while (g.find()) {
			long generationOrGb = Long.parseLong(g.group(1));
			if (generationOrGb <= MAX_NETWORK_GENERATION
					|| isNonFilterAmount(query, g.start(), g.end(), DATA_NON_FILTER_BEFORE, DATA_NON_FILTER_AFTER)) {
				continue;
			}
			tokens.add(new AmountToken(generationOrGb * MB_PER_GB, g.start(), g.end()));
		}
		return rangeOf(query, tokens, false);
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
		return query == null ? null : readTargetGroups(query).group();
	}

	/** 대상 그룹 읽기 결과. group은 대상으로 확정된 그룹(없으면 null), excluded는 질문이 제외한 그룹들. */
	private record GroupRead(String group, Set<String> excluded) {
	}

	/** 질문 속 대상 그룹 말과, 그 말이 제외 표현("말고", "아닌" 등)에 걸렸는지. */
	private record GroupScan(List<GroupMention> mentions, boolean[] negated) {

		/** 질문의 start 위치에 있는 워치·태블릿 말이 제외 표현에 걸려 있는지. */
		boolean negatesDeviceAt(int start) {
			for (int i = 0; i < mentions.size(); i++) {
				GroupMention mention = mentions.get(i);
				boolean device = mention.group().equals("WATCH") || mention.group().equals("TABLET");
				if (device && negated[i] && mention.start() <= start && start < mention.end()) {
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * 대상 그룹 말이 정확히 한 그룹만 가리킬 때만 채택한다(여러 그룹이 섞이면 null).
	 *
	 * <p>"시니어 말고", "청년 아닌"처럼 제외하는 말은 대상으로 세지 않고 제외한 그룹으로 따로 돌려준다. "청년 말고 일반 요금제"는 일반
	 * 하나만 남아 일반으로 읽고 청년을 제외하며, "시니어 말고 3만원대 요금제"는 남는 대상이 없어 대상 조건이 없고 시니어를 제외한다
	 * ("워치나 태블릿 말고"처럼 제외 표현이 이어진 말들에 같이 걸린다). 또 "폰이랑 태블릿 데이터 같이 쓰는 요금제"는 태블릿 전용 요금제를
	 * 찾는 질문이 아니라서 기기 그룹(워치·태블릿)을 대상으로 읽지 않는다.
	 */
	private static GroupRead readTargetGroups(String query) {
		GroupScan scan = scanGroups(query);
		Set<String> groups = new LinkedHashSet<>();
		Set<String> excluded = new LinkedHashSet<>();
		for (int i = 0; i < scan.mentions().size(); i++) {
			(scan.negated()[i] ? excluded : groups).add(scan.mentions().get(i).group());
		}
		if (SHARED_USE_WORD.matcher(query).find() && PHONE_WORD.matcher(query).find()) {
			groups.remove("WATCH");
			groups.remove("TABLET");
		}
		excluded.removeAll(groups);
		return new GroupRead(groups.size() == 1 ? groups.iterator().next() : null, excluded);
	}

	/** 질문에서 대상 그룹 말을 모두 찾고, 각각이 제외 표현에 걸렸는지 정한다. */
	private static GroupScan scanGroups(String query) {
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
		return new GroupScan(mentions, negated);
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
