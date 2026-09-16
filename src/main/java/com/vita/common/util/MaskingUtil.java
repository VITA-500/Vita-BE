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
}
