package com.vita.chat.dto;

import jakarta.validation.constraints.NotBlank;

/** 대화 이름 변경. 길이(1~30자)는 앞뒤 공백을 지운 뒤 서비스에서 검사 */
public record ChatSessionTitleRequest(
        @NotBlank String title) { }
