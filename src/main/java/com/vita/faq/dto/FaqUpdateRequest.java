package com.vita.faq.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import java.util.HashSet;
import java.util.Set;

/** PATCH의 필드 생략과 명시적인 null을 구분한다. */
@Getter
public class FaqUpdateRequest {
    private String category;
    private String subcategory;
    private String question;
    private String answer;
    private String status;
    @JsonIgnore
    private final Set<String> provided = new HashSet<>();

    @JsonSetter public void setCategory(String value) { category = value; provided.add("category"); }
    @JsonSetter public void setSubcategory(String value) { subcategory = value; provided.add("subcategory"); }
    @JsonSetter public void setQuestion(String value) { question = value; provided.add("question"); }
    @JsonSetter public void setAnswer(String value) { answer = value; provided.add("answer"); }
    @JsonSetter public void setStatus(String value) { status = value; provided.add("status"); }

    public boolean has(String field) { return provided.contains(field); }

    @JsonAnySetter
    public void rejectUnknown(String name, JsonNode value) {
        throw new IllegalArgumentException("수정 요청에 허용되지 않는 필드입니다.");
    }
}
