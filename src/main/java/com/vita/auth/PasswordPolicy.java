package com.vita.auth;

import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import java.util.regex.Pattern;

/**
 * 비밀번호 규칙 — 8자 이상, 영문·숫자·특수문자를 각각 1자 이상 포함, 공백 불가.
 *
 * <p>Bean Validation(@Pattern)으로 두지 않는 이유 — 검증 실패가 VALIDATION_ERROR로 뭉뚱그려져
 * 나가는데, 04_API명세서는 비밀번호 규칙 위반을 INVALID_PASSWORD_FORMAT으로 따로 구분한다.
 * FE가 코드만 보고 비밀번호 칸에 안내를 띄울 수 있게 하기 위함이다.
 *
 * <p>회원가입과 내 정보 수정이 같은 규칙을 써야 한다. 한쪽만 바꾸면 가입 때는 막힌 비밀번호로
 * 수정은 되는 구멍이 생긴다.
 */
public final class PasswordPolicy {

	private static final int MIN_LENGTH = 8;
	private static final Pattern LETTER = Pattern.compile("[A-Za-z]");
	private static final Pattern DIGIT = Pattern.compile("[0-9]");
	private static final Pattern SPECIAL = Pattern.compile("[^A-Za-z0-9\\s]");
	private static final Pattern WHITESPACE = Pattern.compile("\\s");

	private PasswordPolicy() {
	}

	public static void validate(String password) {
		if (!isValid(password)) {
			throw new BusinessException(ErrorCode.INVALID_PASSWORD_FORMAT);
		}
	}

	static boolean isValid(String password) {
		return password != null
				&& password.length() >= MIN_LENGTH
				&& LETTER.matcher(password).find()
				&& DIGIT.matcher(password).find()
				&& SPECIAL.matcher(password).find()
				&& !WHITESPACE.matcher(password).find();
	}
}
