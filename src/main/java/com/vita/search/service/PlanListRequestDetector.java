package com.vita.search.service;

import java.util.regex.Pattern;

/**
 * "전체 요금제 알려줘", "요금제 목록 보여줘"처럼 요금제 전체 목록을 요청하는 질문인지 문장 모양으로 판별한다.
 * LLM 호출 없이 상태 없는 순수 함수라 DB 없이 단위 테스트가 된다.
 *
 * <p>이런 질문은 "3만원대"처럼 가격·데이터량 조건이 없어서 조건 매칭 경로를 타지 못하고, 벡터 검색 상위 몇 개와
 * 유사도 threshold로 처리되었다. 그러면 전체 요금제 중 5개만(문구에 따라 0~3개만) 전달되어 LLM이 전체 안내를 못 한다고
 * 답했다. 목록 요청으로 판별되면 {@link PlanSearchService}가 안내 가능한 요금제 전체를 월 요금 순으로 돌려준다.
 *
 * <p>오탐을 줄이려고 세 가지를 함께 본다. 전체를 뜻하는 말이 요금제 단어와 붙어 있는지, 알려 달라는 요청 표현이 있는지,
 * 그리고 해지·변경 같은 행동 요청이나 "가장 싼"처럼 최상급 질문이 아닌지. 가격·데이터량 조건이 있는 질문은 이 판별 전에
 * 조건 매칭이 먼저 처리한다.
 */
public final class PlanListRequestDetector {

	/** 요금제를 가리키는 말. */
	private static final String PLAN = "(?:요금제|플랜)";

	/** 전체를 뜻하는 말. "다"는 아래 요청 동사와 함께 있을 때만 쓴다("데이터 다 쓰면"과 구분). */
	private static final String ALL = "(?:전체|모든|전부|모두|죄다)";

	/** 목록을 알려 달라는 요청 표현. "있나요"는 "모든 요금제에 부가세가 포함돼 있나요"에도 나와서 넣지 않았다. */
	private static final String REQUEST = "(?:알려|보여|말해|설명|소개|나열|뭐뭐|뭐가|뭘|무엇|목록|리스트|종류|궁금|정리|보고 ?싶|알고 ?싶)";

	/** "전체 요금제", "모든 VITA 요금제", "전부 모바일 요금제"처럼 전체를 뜻하는 말이 요금제 앞에 오는 형태. */
	private static final Pattern ALL_BEFORE_PLAN = Pattern.compile(
			ALL + "\\s*(?:의\\s*)?(?:(?:VITA|비타)\\s*)?(?:(?:휴대폰|모바일|스마트폰)\\s*)?" + PLAN);

	/** "요금제 전체 알려줘", "요금제 다 보여줘"처럼 전체를 뜻하는 말이 요금제 뒤에 오는 형태. 요청 동사가 바로 이어져야 한다. */
	private static final Pattern ALL_AFTER_PLAN = Pattern.compile(
			PLAN + "\\s*들?\\s*(?:을|를|은|는|이|가|도)?\\s*(?:" + ALL + "|다)\\s*(?:를|을)?\\s*" + REQUEST);

	/** "요금제 목록", "요금제 종류", "요금제 리스트"처럼 목록을 가리키는 명사가 붙은 형태. */
	private static final Pattern LIST_NOUN = Pattern.compile(PLAN + "\\s*들?\\s*(?:의\\s*)?(?:목록|리스트|종류|라인업)");

	/** "요금제 뭐 있어?", "요금제가 뭐뭐 있어요?", "어떤 요금제가 있나요?", "무슨 요금제 있어?" 형태. */
	private static final Pattern WHAT_EXISTS = Pattern.compile(
			PLAN + "\\s*들?\\s*(?:이|가|는|은|도)?\\s*(?:뭐|뭘|무엇)\\S{0,3}\\s*있"
					+ "|(?:어떤|무슨|뭔)\\s*" + PLAN + "\\S{0,2}\\s*들?\\s*(?:이|가)?\\s*있");

	/** 전체 목록 요청이 아니라 행동 요청이다("전체 요금제 해지하고 싶어요"). */
	private static final Pattern ACTION = Pattern.compile(
			"해지|변경|바꾸|바꿔|바뀌|가입|신청|취소|정지|이동|해제|납부|청구|위약|약정|인상|인하");

	/** 최상급·정렬 질문. 가장 싼 요금제 같은 질문은 별도 조회 경로가 처리한다. */
	private static final Pattern SUPERLATIVE = Pattern.compile("가장|제일|최저|최고|최소|최대|젤");

	private PlanListRequestDetector() {
	}

	/**
	 * 요금제 전체 목록을 요청하는 질문이면 true.
	 *
	 * @param query 사용자 질문 원문 또는 질문 변환 결과(요금제용 질문). null이나 빈 문자열이면 false.
	 */
	public static boolean matches(String query) {
		if (query == null || query.isBlank()) {
			return false;
		}
		if (ACTION.matcher(query).find() || SUPERLATIVE.matcher(query).find()) {
			return false;
		}
		if (LIST_NOUN.matcher(query).find() || WHAT_EXISTS.matcher(query).find() || ALL_AFTER_PLAN.matcher(query).find()) {
			return true;
		}
		return ALL_BEFORE_PLAN.matcher(query).find() && Pattern.compile(REQUEST).matcher(query).find();
	}
}
