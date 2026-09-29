package com.vita.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 04_API명세서 회원가입 400 INVALID_PASSWORD_FORMAT — 8자 이상, 영문/숫자/특수문자 포함. */
class PasswordPolicyTest {

	@ParameterizedTest
	@ValueSource(strings = {"P@ssw0rd123", "NewP@ss123", "a1!aaaaa"})
	@DisplayName("영문·숫자·특수문자를 모두 포함한 8자 이상은 통과한다")
	void acceptsValidPasswords(String password) {
		assertThat(PasswordPolicy.isValid(password)).isTrue();
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {
			"a1!aaaa",       // 7자
			"password123",   // 특수문자 없음
			"password!!!",   // 숫자 없음
			"12345678!",     // 영문 없음
			"pass word1!"    // 공백 포함
	})
	@DisplayName("규칙을 하나라도 어기면 거부한다")
	void rejectsInvalidPasswords(String password) {
		assertThat(PasswordPolicy.isValid(password)).isFalse();
	}

	@Test
	@DisplayName("위반 시 VALIDATION_ERROR가 아닌 INVALID_PASSWORD_FORMAT으로 응답한다")
	void throwsDedicatedErrorCode() {
		assertThatThrownBy(() -> PasswordPolicy.validate("password123"))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.INVALID_PASSWORD_FORMAT);
	}
}
