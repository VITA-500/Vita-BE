package com.vita.chat.dto;

/** 기본 데이터량 범위 조건(MB). 없는 쪽은 null. 무제한 요금제는 상한이 없을 때만 포함된다. */
public record DataRange(Long minMb, Long maxMb) {

    public static DataRange none() {
        return new DataRange(null, null);
    }

    public boolean isEmpty() {
        return minMb == null && maxMb == null;
    }

    public boolean contains(boolean unlimited, Number baseDataMb) {
        if (isEmpty()) {
            return true;
        }
        if (unlimited) {
            return maxMb == null;   // "N GB 이상"에는 무제한 포함(BE3 규칙), 상한이 있으면 제외
        }
        if (baseDataMb == null) {
            return false;
        }
        long mb = baseDataMb.longValue();
        return (minMb == null || mb >= minMb) && (maxMb == null || mb <= maxMb);
    }
}