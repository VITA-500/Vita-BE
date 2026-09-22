package com.vita.faq.collection;

/** LG U+ FAQ 수집 중 외부 API 호출 또는 응답 검증 실패를 나타내는 예외. */
public class LguFaqCollectionException extends RuntimeException {

	public LguFaqCollectionException(String message) {
		super(message);
	}

	public LguFaqCollectionException(String message, Throwable cause) {
		super(message, cause);
	}
}
