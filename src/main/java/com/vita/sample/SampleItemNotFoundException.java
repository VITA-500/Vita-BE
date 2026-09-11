package com.vita.sample;

import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;

/**
 * 도메인 예외는 서브패키지 없이 도메인 루트에 바로 둔다 (08_개발표준 1절).
 * 실제 도메인(auth/faq/chat/store 등)에서는 FaqNotFoundException, InvalidTokenException처럼
 * 이 클래스와 같은 패턴으로 만들면 된다.
 */
public class SampleItemNotFoundException extends BusinessException {

	public SampleItemNotFoundException() {
		super(ErrorCode.SAMPLE_ITEM_NOT_FOUND);
	}
}
