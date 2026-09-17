package com.vita.faq.collection.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** LG U+ FAQ 목록에 포함된 질문 한 건의 식별자와 분류 정보. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LguFaqListItem(
	String kbId,
	String title,
	String nodeId,
	String nodeName,
	String updatedDate,
	String nodePath
) {
}
