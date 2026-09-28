package com.vita.search.entity;

/** FAQ 노출 상태. INACTIVE는 하드 삭제 대신 검색 대상에서만 제외하기 위한 값이다. */
public enum FaqStatus {
	/** 검색 결과에 노출됨. */
	ACTIVE,
	/** 삭제 처리된 FAQ. row는 남아있지만 검색·조회에서 제외된다. */
	INACTIVE
}
