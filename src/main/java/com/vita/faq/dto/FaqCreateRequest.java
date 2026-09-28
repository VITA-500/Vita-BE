package com.vita.faq.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FaqCreateRequest(
    @NotBlank @Size(max = 50) String category,
    @Size(max = 50) String subcategory,
    @NotBlank String question,
    @NotBlank String answer
) {
    @JsonAnySetter
    public void rejectUnknown(String name, JsonNode value) {
        throw new IllegalArgumentException("등록 요청에 허용되지 않는 필드입니다.");
    }
}
