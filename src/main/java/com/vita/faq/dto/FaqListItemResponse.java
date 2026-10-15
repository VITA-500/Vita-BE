package com.vita.faq.dto;

import java.time.LocalDateTime;

/** 관리자 목록과 수정 화면에 필요한 질문·답변 및 생성·수정 시각을 제공한다. */
public record FaqListItemResponse(
        long faqId,
        String category,
        String subcategory,
        String question,
        String answer,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) { }
