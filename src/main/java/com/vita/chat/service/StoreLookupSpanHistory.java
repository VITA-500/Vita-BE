package com.vita.chat.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.vita.chat.dto.ChatStoreMap;
import com.vita.chat.dto.StoreIntent;
import com.vita.chat.service.ChatStoreFinder.StoreLookup;

/**
 * 매장 조회(store_lookup) 단계의 history를 만든다. 위치 좌표(위도·경도)는 개인정보라 저장하지 않고, 위치를 받았는지 여부만 남긴다.
 * 조회에 쓴 장소 이름은 질문에 적힌 말이라 의도와 함께 그대로 남긴다.
 */
final class StoreLookupSpanHistory {

    private StoreLookupSpanHistory() {
    }

    /**
     * @param intent           질문 변환이 읽은 매장 의도
     * @param locationProvided 사용자가 위치를 보냈는지(좌표 값 자체는 저장하지 않는다)
     * @param lookup           조회 결과
     */
    static Map<String, Object> of(StoreIntent intent, boolean locationProvided, StoreLookup lookup) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("store_intent", TransformSpanHistory.storeIntent(intent));
        map.put("location_provided", locationProvided);

        ChatStoreMap storeMap = lookup.storeMap();
        List<Map<String, Object>> results = new ArrayList<>();
        if (storeMap != null) {
            map.put("map_type", storeMap.type());
            map.put("origin_source", storeMap.originSource());
            for (ChatStoreMap.Store store : storeMap.stores()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", store.name());
                item.put("distance_text", store.distanceText());
                item.put("open_now", store.openNow());
                results.add(item);
            }
        }
        map.put("found", results.size());
        map.put("results", results);
        map.put("error", lookup.error());
        return map;
    }
}
