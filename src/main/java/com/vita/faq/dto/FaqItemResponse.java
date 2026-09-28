package com.vita.faq.dto;

import java.time.LocalDateTime;

/** 목록과 등록의 공개 필드만 포함한다. 답변과 임베딩은 직렬화하지 않는다. */
public record FaqItemResponse(long faqId, String category, String subcategory,
                              String question, String status, LocalDateTime createdAt) { }
