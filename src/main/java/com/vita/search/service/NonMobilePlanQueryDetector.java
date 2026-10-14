package com.vita.search.service;

import java.util.regex.Pattern;

/**
 * 모바일이 아닌 서비스(인터넷, IPTV, TV, 인터넷전화, CCTV)의 요금·상품을 묻는 질문인지 문장 모양(정규식)으로 알아본다.
 *
 * <p>요금제 검색은 모바일 요금제(plans 테이블)만 다룬다. "인터넷 월 요금은 얼마예요?"처럼 모바일이 아닌 서비스의 요금을
 * 물어도 임베딩 유사도가 threshold를 넘으면 엉뚱한 모바일 요금제가 답으로 나가므로, 이런 질문은 요금제 검색을 건너뛴다.
 * FAQ 검색은 그대로 하므로 인터넷·IPTV 요금 FAQ가 있으면 그 답이 나간다.
 *
 * <p>서비스 이름과 요금 단어가 붙어 있을 때만 잡는다("인터넷 요금", "IPTV 월 요금", "TV 요금제"). "인터넷 많이 쓰는데
 * 요금제 추천해줘", "TV 많이 보는데 어떤 요금제가 좋아요?"처럼 모바일 요금제를 묻는 문장은 두 단어가 떨어져 있어
 * 잡히지 않는다. 휴대폰·모바일 같은 말이 함께 있으면 모바일 질문으로 보고 잡지 않는다. 상태가 없는 순수 함수라 DB 없이
 * 단위 테스트가 된다.
 */
public final class NonMobilePlanQueryDetector {

	/** 모바일이 아닌 서비스 이름. "기가 인터넷", "가정용 인터넷"처럼 앞에 수식어가 붙어도 서비스 이름 부분만 맞으면 된다. */
	private static final String SERVICE =
			"(?:인터넷전화|인터넷|IPTV|iptv|아이피티비|(?<![A-Za-z])TV(?![A-Za-z])|티비|티브이|CCTV|cctv|시시티비|셋톱박스)";

	/** 요금·상품을 가리키는 말. */
	private static final String FEE_WORD = "(?:요금제|요금|가격|비용|상품|플랜|할인|약정|사은품|월정액)";

	/**
	 * 서비스 이름 바로 뒤에 요금 단어가 오는 모양. "인터넷 월 요금", "IPTV 상품 가격", "TV 요금제", "CCTV 설치 비용".
	 * 사이에는 서비스·상품·설치·가입·이용·월 정도만 허용해 "인터넷 많이 쓰는데 요금제"처럼 떨어진 문장은 잡지 않는다.
	 */
	private static final Pattern SERVICE_THEN_FEE = Pattern.compile(
			SERVICE + "\\s*(?:(?:서비스|상품|설치|가입|이용|월)\\s*)*" + FEE_WORD);

	/** "인터넷 얼마예요?", "IPTV 몇 원이에요?"처럼 요금 단어 없이 금액을 묻는 모양. */
	private static final Pattern SERVICE_THEN_HOW_MUCH = Pattern.compile(
			SERVICE + "\\s*(?:월\\s*)?(?:얼마|몇\\s*원)");

	/** 모바일을 가리키는 말. 있으면 모바일 요금제를 묻는 질문으로 본다("모바일 인터넷 요금제", "폰 인터넷 요금"). */
	private static final Pattern MOBILE_MARKER = Pattern.compile(
			"휴대폰|핸드폰|모바일|스마트폰|무선|(?<![가-힣])폰(?![가-힣])");

	private NonMobilePlanQueryDetector() {
	}

	/**
	 * 모바일이 아닌 서비스의 요금·상품을 묻는 질문인지 판별한다.
	 *
	 * @param query 사용자 질문 원문(변환 전)
	 * @return 인터넷·IPTV·TV·인터넷전화·CCTV 요금·상품 질문이면 true
	 */
	public static boolean matches(String query) {
		if (query == null || query.isBlank()) {
			return false;
		}
		if (MOBILE_MARKER.matcher(query).find()) {
			return false;
		}
		return SERVICE_THEN_FEE.matcher(query).find() || SERVICE_THEN_HOW_MUCH.matcher(query).find();
	}
}
