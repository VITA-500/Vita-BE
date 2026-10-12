package com.vita.chat.dto;

import java.math.BigDecimal;
import java.util.List;

import com.vita.store.dto.response.BenefitStoreListResponse;
import com.vita.store.dto.response.StoreChatItemResponse;

/**
 * 채팅 답변의 매장 지도.
 * FE ChatStoreMap(features/chat/types.ts)과 같은 모양
 * type·category·benefitId는 BE에서 추가한 칸이라 FE가 쓰지 않아도 된다.
 *
 * @param type           PHONE(통신 매장) / PARTNER(제휴 매장) — /stores/nearby의 storeType과 같은 값
 * @param activeServices 질문에 나온 서비스 (요청 서비스 매장 강조용). 없으면 빈 목록
 * @param originSource   항상 "current" (사용자가 보낸 위치 기준)
 */
public record ChatStoreMap(String type, String category, Long benefitId, List<String> activeServices,
                           Origin origin, String originSource, List<Store> stores) {

    public record Origin(double lat, double lng) {
    }

    /** id는 FE 형식에 맞춰 문자열.
     * 제휴 매장은 상담 및 제공 서비스와 전화번호가 비어 있다
     */
    public record Store(String id, String name, String address, String businessHours,
                        List<String> consultServices, String phone, List<String> providedServices,
                        double lat, double lng, String distanceText, Boolean openNow) {
    }

    public static ChatStoreMap ofPhoneStores(List<StoreChatItemResponse> stores, List<String> activeServices,
                                             BigDecimal lat, BigDecimal lng) {
        return new ChatStoreMap("PHONE", null, null, activeServices, origin(lat, lng), "current",
                stores.stream()
                        .map(s -> new Store(String.valueOf(s.storeId()), s.name(), s.address(), s.businessHours(),
                                s.consultServices(), s.phone() == null ? "" : s.phone(), s.providedServices(),
                                s.lat().doubleValue(), s.lng().doubleValue(), distanceText(s.distanceKm()), s.openNow()))
                        .toList());
    }

    public static ChatStoreMap ofPartnerStores(String category, Long benefitId,
                                               List<BenefitStoreListResponse.Item> stores, BigDecimal lat, BigDecimal lng) {
        return new ChatStoreMap("PARTNER", category, benefitId, List.of(), origin(lat, lng), "current",
                stores.stream()
                        .map(s -> new Store(String.valueOf(s.storeId()), s.name(), s.address(), s.businessHours(),
                                List.of(), "", List.of(),
                                s.lat().doubleValue(), s.lng().doubleValue(), distanceText(s.distanceKm()), s.openNow()))
                        .toList());
    }

    private static Origin origin(BigDecimal lat, BigDecimal lng) {
        return new Origin(lat.doubleValue(), lng.doubleValue());
    }

    /** FE 표기와 같은 "약 320m" / "약 1.1km" */
    static String distanceText(double km) {
        if (km < 1) {
            return "약 " + Math.max(10, Math.round(km * 100) * 10) + "m";
        }
        return "약 " + Math.round(km * 10) / 10.0 + "km";
    }
}