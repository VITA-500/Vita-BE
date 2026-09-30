package com.vita.faq.dto;

import java.time.LocalDateTime;

/** FAQ 등록 응답 필드만 포함하며 답변과 임베딩은 노출하지 않는다. */
public record FaqItemResponse(long faqId, String category, String subcategory,
                              String question, String status, LocalDateTime createdAt) { }
