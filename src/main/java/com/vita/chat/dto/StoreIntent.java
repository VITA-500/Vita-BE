package com.vita.chat.dto;

import java.time.LocalTime;
import java.util.List;

/**
 * 매장 질문 정보
 * PHONE: 통신 매장 위치 및 상담, PARTNER: 제휴 매장, NONE: 매장 질문 아님
 * @param type
 * @param category  PARTNER일 때 업종(카페, 영화 등)
 * @param brand     PARTNER일 때 질문에 나온 브랜드 이름
 * @param services  PHONE일 때 질문에 나온 상담 및 제공 서비스
 * @param openNow   영업 중 조건
 * @param openAt    특정 시각에 영업 중 조건
 */
public record StoreIntent(Type type, String category, String brand, List<String> services,
                          boolean openNow, LocalTime openAt) {
    public enum Type { NONE, PHONE, PARTNER }

    private static final StoreIntent NONE = new StoreIntent(Type.NONE, null, null,
            List.of(), false, null);

    public static StoreIntent none() {
        return NONE;
    }

    public boolean isNone() {
        return type == Type.NONE;
    }
}
