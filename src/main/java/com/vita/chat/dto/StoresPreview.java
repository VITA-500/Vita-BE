package com.vita.chat.dto;

import com.vita.store.dto.response.BenefitStoreListResponse;
import com.vita.store.dto.response.StoreChatItemResponse;

import java.math.BigDecimal;
import java.util.List;

/**
 * 채팅 미니맵용 매장 목록
 * @param type      PHONE(통신 매장) / PARTNER(제휴 매장)
 * @param category  PARTNER일 때 업종
 * @param benefitId PARTNER이고 브랜드를 지정한 질문일 때 혜택 id
 * @param mapLink
 * @param stores
 */

public record StoresPreview(String type, String category, Long benefitId, String mapLink,
                            List<Item> stores) {
    public record Item(Long storeId, String name, BigDecimal lat, BigDecimal lng,
                       Double distanceKm, String businessHours, Boolean openNow) { }

    public static StoresPreview ofPhoneStores(List<StoreChatItemResponse> stores) {
        return new StoresPreview("PHONE", null, null, "/map", stores.stream()
                .map(s -> new Item(s.storeId(), s.name(), s.lat(), s.lng(),
                        s.distanceKm(), s.businessHours(), s.openNow()))
                .toList());
    }

    public static StoresPreview ofPartnerStores(String category, Long benefitId,
                                                List<BenefitStoreListResponse.Item> stores) {
        return new StoresPreview("PARTNER", category, benefitId, "/map?benefit=" + category, stores.stream()
                .map(s -> new Item(s.storeId(), s.name(), s.lat(), s.lng(),
                        s.distanceKm(), s.businessHours(), s.openNow()))
                .toList());
    }
}
