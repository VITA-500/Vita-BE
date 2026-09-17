package com.vita.faq.collection.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** LG U+ FAQ 상세 API가 반환하는 질문, HTML 답변 및 공식 분류 경로. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LguFaqDetailResponse(
	String kbId,
	String title,
	String contents,
	String nodeId,
	List<String> pathNode,
	String pathNodeStr
) {
}
