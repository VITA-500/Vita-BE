package com.vita.common.util;

/** PII 마스킹 — 로그와 응답에서 개인정보를 가린다 (Phase 2 PII 마스킹 과제). */
public final class MaskingUtil {

	private MaskingUtil() {
	}

	/** hong@example.com -> ho**@example.com. 로컬파트가 2자 이하면 전부 가린다. */
	public static String email(String email) {
		if (email == null || email.isBlank()) {
			return email;
		}
		int at = email.indexOf('@');
		if (at < 0) {
			return "**";
		}
		if (at <= 2) {
			return "**" + email.substring(at);
		}
		return email.substring(0, 2) + "*".repeat(at - 2) + email.substring(at);
	}

	/** 010-1234-5678 -> 010-****-5678 */
	public static String phone(String phone) {
		if (phone == null || phone.isBlank()) {
			return phone;
		}
		String digits = phone.replaceAll("[^0-9]", "");
		if (digits.length() < 7) {
			return "***";
		}
		String head = digits.substring(0, 3);
		String tail = digits.substring(digits.length() - 4);
		return head + "-****-" + tail;
	}

	// 숫자 앞뒤에 다른 숫자가 붙어 있으면 더 긴 숫자의 일부이므로 건드리지 않는다.
	private static final java.util.regex.Pattern RRN =
			java.util.regex.Pattern.compile("(?<!\\d)(\\d{6})[- ]?([1-4])\\d{6}(?!\\d)");
	private static final java.util.regex.Pattern PHONE =
			java.util.regex.Pattern.compile("(?<!\\d)(0\\d{1,2})[-. ]?(\\d{3,4})[-. ]?(\\d{4})(?!\\d)");
	// 하이픈으로 나뉜 10~16자리 숫자(계좌번호 모양)와, 하이픈 없는 12~16자리 숫자
	private static final java.util.regex.Pattern ACCOUNT_HYPHEN =
			java.util.regex.Pattern.compile("(?<![\\d-])\\d{2,6}(?:-\\d{2,8}){1,3}(?![\\d-])");
	private static final java.util.regex.Pattern ACCOUNT_PLAIN =
			java.util.regex.Pattern.compile("(?<!\\d)\\d{12,16}(?!\\d)");
	private static final java.util.regex.Pattern EMAIL =
			java.util.regex.Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

	/**
	 * 자유 문장 속의 개인정보를 가린다. 질문 원문·프롬프트처럼 어디에 무엇이 들어 있을지 모르는 글에 쓴다.
	 * 주민등록번호(앞 7자리 외 가림), 전화번호(010-****-5678), 계좌번호 모양의 긴 숫자(끝 4자리 외 가림), 이메일(ho**@도메인)을 찾는다.
	 * 날짜(2026-10-19)나 금액(33,000)처럼 짧은 숫자는 건드리지 않는다.
	 */
	public static String text(String text) {
		if (text == null || text.isEmpty()) {
			return text;
		}
		String masked = RRN.matcher(text).replaceAll(m -> m.group(1) + "-" + m.group(2) + "******");
		masked = PHONE.matcher(masked).replaceAll(m -> m.group(1) + "-****-" + m.group(3));
		masked = ACCOUNT_HYPHEN.matcher(masked).replaceAll(m -> maskDigitsExceptLast4(m.group()));
		masked = ACCOUNT_PLAIN.matcher(masked).replaceAll(m -> maskDigitsExceptLast4(m.group()));
		return EMAIL.matcher(masked).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(email(m.group())));
	}

	/** 숫자 사이 하이픈은 그대로 두고, 끝 4자리를 뺀 숫자를 *로 바꾼다. 숫자가 10자리 미만이면(날짜 등) 그대로 둔다. */
	private static String maskDigitsExceptLast4(String candidate) {
		int digitCount = candidate.replaceAll("\\D", "").length();
		if (digitCount < 10 || digitCount > 16) {
			return java.util.regex.Matcher.quoteReplacement(candidate);
		}
		StringBuilder out = new StringBuilder();
		int seen = 0;
		for (char c : candidate.toCharArray()) {
			if (Character.isDigit(c)) {
				seen++;
				out.append(seen > digitCount - 4 ? c : '*');
			} else {
				out.append(c);
			}
		}
		return java.util.regex.Matcher.quoteReplacement(out.toString());
	}
}
