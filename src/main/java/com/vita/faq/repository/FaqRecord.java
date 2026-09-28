package com.vita.faq.repository;

public record FaqRecord(long id, String category, String subcategory,
                        String question, String answer, String status) { }
