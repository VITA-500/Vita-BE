package com.vita.faq.dto;
import java.time.LocalDateTime;
public record FaqUpdateResponse(long faqId, LocalDateTime updatedAt) { }
