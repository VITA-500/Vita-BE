package com.vita.chat.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.vita.chat.service.ChatContextBuilder.ExtremeLookup;
import com.vita.search.dto.PlanReference;
import com.vita.search.pipeline.RetrievalSpanHistory;

/**
 * 극값 조회(plan_lookup) 단계의 history를 만든다. "가장 저렴한 요금제" 같은 질문이 어떤 기준과 범위로 조회됐고 무엇이 context에
 * 들어갔는지 남긴다. 요금제는 저장 당시의 원문(이름, 월 요금, 설명)을 복사하고, 찾기용으로 plan_code를 함께 둔다.
 */
final class PlanLookupSpanHistory {

    private PlanLookupSpanHistory() {
    }

    /**
     * @param lookup           극값 조회의 입력과 결과
     * @param fallbackToSearch 조회 결과가 비어 일반 검색으로 넘어가는지(정형 극값 질문에서만 일어난다)
     * @param afterFallback    일반 검색으로 넘어온 뒤 다시 한 조회인지
     */
    static Map<String, Object> of(ExtremeLookup lookup, boolean fallbackToSearch, boolean afterFallback) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (afterFallback) {
            map.put("fallback_of", "dbOnly");
        }
        map.put("sort_key", lookup.sortKey() == null ? null : lookup.sortKey().name());
        map.put("limit", lookup.limit());
        map.put("scope_group", lookup.scopeGroup());
        map.put("conditions", RetrievalSpanHistory.conditions(lookup.conditions()));
        map.put("price_range", range("min_won", lookup.priceRange().minWon(), "max_won", lookup.priceRange().maxWon()));
        map.put("data_range", range("min_mb", lookup.dataRange().minMb(), "max_mb", lookup.dataRange().maxMb()));
        map.put("non_mobile_guard", lookup.nonMobileGuard());
        map.put("fetched", lookup.fetched());
        map.put("in_range", lookup.inRange());
        map.put("returned", returned(lookup.returned()));
        map.put("ties_kept", lookup.returned().size() > lookup.limit());
        map.put("no_match", lookup.noMatch());
        map.put("fallback_to_search", fallbackToSearch);
        return map;
    }

    /** 값이 있는 쪽만 담는다. 둘 다 없으면 빈 Map. */
    private static Map<String, Object> range(String minKey, Long min, String maxKey, Long max) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (min != null) {
            map.put(minKey, min);
        }
        if (max != null) {
            map.put(maxKey, max);
        }
        return map;
    }

    private static List<Map<String, Object>> returned(List<PlanReference> plans) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (PlanReference plan : plans) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("plan_code", plan.planCode());
            item.put("name", plan.name());
            item.put("monthly_fee", plan.monthlyFee());
            item.put("description", plan.description());
            list.add(item);
        }
        return list;
    }
}
