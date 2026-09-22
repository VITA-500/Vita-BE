package com.vita.faq.collection;

/** LG U+가 요청 제한과 함께 안내한 재시도 대기 시간을 전달한다. */
public class LguFaqRateLimitException extends LguFaqCollectionException {

	private final long retryAfterSeconds;

	public LguFaqRateLimitException(long retryAfterSeconds, Throwable cause) {
		super("LG U+ FAQ 요청 제한이 적용되었습니다.", cause);
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public long retryAfterSeconds() {
		return retryAfterSeconds;
	}
}
