package com.vita.search.hybrid;

import java.util.regex.Pattern;

/**
 * 키워드(BM25) 검색에 넣을 질문 텍스트를 만든다.
 *
 * <p>질문 변환이 만든 질문은 {@code 조건: ...}, {@code 질문: ...}, {@code 핵심 키워드: ...}(요금제는 {@code 금액:}, {@code 데이터:},
 * {@code 대상:} 포함)처럼 줄 앞에 라벨이 붙은 형태다. 라벨 단어("조건", "질문", "핵심 키워드" 등)는 요금제·FAQ 설명에 흔해서 BM25가 거의
 * 모든 문서에 걸리게 하므로, <b>줄 앞의 라벨만 떼고 값은 전부 쓴다</b>. 라벨이 없는 텍스트(원문, 변환이 폴백된 질문)는 그대로 쓴다.
 * 벡터 검색에는 이 처리를 하지 않고 원래 질문 그대로 임베딩한다.
 */
public final class Bm25QueryText {

	private static final Pattern LABEL = Pattern.compile("^\\s*(조건|질문|핵심 키워드|금액|데이터|대상)\\s*:\\s*");

	private Bm25QueryText() {
	}

	/**
	 * @return 줄 앞의 라벨을 뗀 값을 공백으로 이은 텍스트. 라벨을 떼고 나면 비면 원래 텍스트를 다듬어 돌려준다. 입력이 null이거나 비면 빈 문자열
	 */
	public static String from(String text) {
		if (text == null || text.isBlank()) {
			return "";
		}
		StringBuilder joined = new StringBuilder();
		for (String line : text.split("\\R")) {
			String value = LABEL.matcher(line).replaceFirst("").strip();
			if (!value.isEmpty()) {
				if (joined.length() > 0) {
					joined.append(' ');
				}
				joined.append(value);
			}
		}
		return joined.length() > 0 ? joined.toString() : text.strip();
	}
}
