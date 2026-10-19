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
 * @param place     질문에 나온 장소 이름 (예: "방학역"). 있으면 사용자 위치 대신 이 장소 기준으로 찾는다
 * @param storeName 질문에 나온 매장 이름 (예: "비타 강남역 8번출구점"). 있으면 그 매장을 먼저 찾는다
 * @param otherPlaces 장소가 여러 개일 때 첫 번째(place)를 뺀 나머지. 한 번에 한 장소만 찾으므로 안내에만 쓴다
 * @param minKm     "1km 이상"처럼 최소 거리 조건 (없으면 null)
 * @param maxKm     "500m 이내"처럼 최대 거리 조건 (없으면 null)
 */
public record StoreIntent(Type type, String category, String brand, List<String> services,
                          boolean openNow, LocalTime openAt, String place, String storeName,
                          List<String> otherPlaces, Double minKm, Double maxKm) {
    public enum Type { NONE, PHONE, PARTNER }

    private static final StoreIntent NONE = new StoreIntent(Type.NONE, null, null,
            List.of(), false, null, null, null);

    /** 장소 하나, 거리 조건 없음 */
    public StoreIntent(Type type, String category, String brand, List<String> services,
                       boolean openNow, LocalTime openAt, String place, String storeName) {
        this(type, category, brand, services, openNow, openAt, place, storeName, List.of(), null, null);
    }

    public static StoreIntent none() {
        return NONE;
    }

    public boolean isNone() {
        return type == Type.NONE;
    }
}
