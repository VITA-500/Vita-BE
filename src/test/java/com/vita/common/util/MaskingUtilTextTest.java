package com.vita.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 자유 문장 속 개인정보 마스킹이 전화번호·계좌번호·주민번호·이메일은 가리고, 날짜·금액 같은 짧은 숫자는 건드리지 않는지 확인한다. */
class MaskingUtilTextTest {

	@Test
	void masksPhoneNumbersInTheirCommonWritings() {
		assertThat(MaskingUtil.text("제 번호는 010-1234-5678 입니다")).isEqualTo("제 번호는 010-****-5678 입니다");
		assertThat(MaskingUtil.text("01012345678로 연락주세요")).isEqualTo("010-****-5678로 연락주세요");
		assertThat(MaskingUtil.text("010 1234 5678")).isEqualTo("010-****-5678");
		assertThat(MaskingUtil.text("서울 02-123-4567")).isEqualTo("서울 02-****-4567");
	}

	@Test
	void masksAccountLikeNumbersExceptTheLastFourDigits() {
		assertThat(MaskingUtil.text("계좌 110-123-456789 로 보내줘")).isEqualTo("계좌 ***-***-**6789 로 보내줘");
		assertThat(MaskingUtil.text("1002123456789012")).isEqualTo("************9012");
		// 13자리 숫자는 주민등록번호 모양과 구분되지 않아 그쪽 규칙으로 가려진다(어느 쪽이든 가려지는 것이 안전하다).
		assertThat(MaskingUtil.text("1002123456789")).doesNotContain("123456789");
	}

	@Test
	void masksResidentRegistrationNumbersAndEmails() {
		assertThat(MaskingUtil.text("900101-1234567")).isEqualTo("900101-1******");
		assertThat(MaskingUtil.text("메일은 hong@example.com 입니다")).isEqualTo("메일은 ho**@example.com 입니다");
	}

	@Test
	void leavesDatesAmountsAndShortNumbersAlone() {
		String text = "2026-10-19에 월 33,000원 요금제 5G 100GB 1588-0500 문의";

		assertThat(MaskingUtil.text(text)).isEqualTo(text);
		assertThat(MaskingUtil.text("2026-10-14T10:20:00")).isEqualTo("2026-10-14T10:20:00");
	}

	@Test
	void handlesNullAndEmptyText() {
		assertThat(MaskingUtil.text(null)).isNull();
		assertThat(MaskingUtil.text("")).isEmpty();
	}
}
