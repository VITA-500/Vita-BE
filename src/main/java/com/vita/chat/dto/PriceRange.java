package com.vita.chat.dto;

/** 월 요금 범위 조건(원). 없는 쪽은 null. 예: "3만원대" -> (30000, 39999) */
public record PriceRange(Long minWon, Long maxWon) {

    public static PriceRange none() {
        return new PriceRange(null, null);
    }

    public boolean isEmpty() {
        return minWon == null && maxWon == null;
    }

    public boolean contains(long monthlyFee) {
        return (minWon == null || monthlyFee >= minWon)
                && (maxWon == null || monthlyFee <= maxWon);
    }
}