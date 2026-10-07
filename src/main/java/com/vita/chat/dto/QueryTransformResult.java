package com.vita.chat.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record QueryTransformResult(
        @JsonProperty("faq_query") String faqQuery,
        @JsonProperty("plan_query") String planQuery) {

    /** 변환 실패 시 원문으로 양쪽을 검색하기 위한 폴백 */
    public static QueryTransformResult original(String question) {
        return new QueryTransformResult(question, question);
    }
}