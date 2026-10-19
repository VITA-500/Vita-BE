package com.vita.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vita.chat.dto.ChatStoreMap;
import com.vita.chat.dto.DataRange;
import com.vita.chat.dto.PriceRange;
import com.vita.chat.dto.StoreIntent;
import com.vita.chat.service.ChatContextBuilder.ExtremeLookup;
import com.vita.chat.service.ChatStoreFinder.StoreLookup;
import com.vita.search.dto.PlanReference;
import com.vita.search.service.PlanQueryConditions;
import com.vita.search.service.PlanSortKey;

/** 극값 조회·매장 조회 단계의 history가 입력과 결과를 담고, 위치 좌표는 담지 않는지 확인한다. */
class LookupSpanHistoryTest {

    private static PlanReference plan(String code, int fee) {
        return new PlanReference(1L, code, "이름" + code, "요약", fee, "설명 원문", 1.0, null,
                "5G", "GENERAL", null, null, "UNLIMITED", null, null, "UNLIMITED", null, "UNLIMITED", null);
    }

    private static ExtremeLookup lookup(List<PlanReference> returned, int limit, boolean noMatch) {
        return new ExtremeLookup(PlanSortKey.MOST_DATA, limit, "GENERAL",
                new PlanQueryConditions(null, 50_000, null, null, null, null), new PriceRange(null, 50_000L),
                DataRange.none(), false, 3, returned.size(), returned, noMatch);
    }

    @Test
    @SuppressWarnings("unchecked")
    void planLookupHistoryHasInputsCountsAndTheReturnedPlanSnapshots() throws Exception {
        Map<String, Object> history = PlanLookupSpanHistory.of(
                lookup(List.of(plan("VITA-MAX", 69_000), plan("VITA-MAX-SHARE", 75_000)), 1, false), false, false);

        assertThat(history).containsEntry("sort_key", "MOST_DATA").containsEntry("limit", 1)
                .containsEntry("scope_group", "GENERAL").containsEntry("fetched", 3).containsEntry("in_range", 2)
                .containsEntry("ties_kept", true).containsEntry("no_match", false)
                .containsEntry("fallback_to_search", false).doesNotContainKey("fallback_of");
        assertThat((Map<String, Object>) history.get("conditions")).containsEntry("fee_max", 50_000);
        assertThat((Map<String, Object>) history.get("price_range")).containsOnly(Map.entry("max_won", 50_000L));
        assertThat((Map<String, Object>) history.get("data_range")).isEmpty();
        List<Map<String, Object>> returned = (List<Map<String, Object>>) history.get("returned");
        assertThat(returned.get(0)).containsEntry("plan_code", "VITA-MAX").containsEntry("monthly_fee", 69_000)
                .containsEntry("description", "설명 원문");
        assertThat(new ObjectMapper().writeValueAsString(history)).contains("\"returned\"");
    }

    @Test
    void planLookupHistoryMarksFallbackBothWays() {
        Map<String, Object> toSearch = PlanLookupSpanHistory.of(lookup(List.of(), 1, true), true, false);
        Map<String, Object> repeated = PlanLookupSpanHistory.of(lookup(List.of(), 1, true), false, true);

        assertThat(toSearch).containsEntry("fallback_to_search", true).containsEntry("no_match", true)
                .containsEntry("ties_kept", false);
        assertThat(repeated).containsEntry("fallback_of", "dbOnly").containsEntry("fallback_to_search", false);
    }

    private static ChatStoreMap mapWith(String name, String distanceText) {
        ChatStoreMap.Store store = new ChatStoreMap.Store("1", name, "주소", "09:00-18:00", List.of(), "", List.of(),
                37.5, 127.0, distanceText, true, null, null, null, null);
        return new ChatStoreMap("PHONE", null, null, List.of(), new ChatStoreMap.Origin(37.5, 127.0), "place", "방학역",
                List.of(store));
    }

    @Test
    @SuppressWarnings("unchecked")
    void storeLookupHistoryHasIntentFoundStoresAndNoCoordinates() throws Exception {
        StoreIntent intent = new StoreIntent(StoreIntent.Type.PHONE, null, null, List.of(), false, null, "방학역", null);
        StoreLookup lookup = new StoreLookup("<store_result/>", mapWith("비타 방학점", "0.8km"));

        Map<String, Object> history = StoreLookupSpanHistory.of(intent, true, lookup);

        assertThat(history).containsEntry("location_provided", true).containsEntry("found", 1)
                .containsEntry("map_type", "PHONE").containsEntry("origin_source", "place")
                .containsEntry("error", null);
        assertThat((Map<String, Object>) history.get("store_intent")).containsEntry("type", "PHONE")
                .containsEntry("place", "방학역");
        assertThat(((List<Map<String, Object>>) history.get("results")).get(0))
                .containsEntry("name", "비타 방학점").containsEntry("distance_text", "0.8km");
        String json = new ObjectMapper().writeValueAsString(history);
        assertThat(json).doesNotContain("37.5").doesNotContain("127.0").doesNotContain("lat").doesNotContain("lng");
    }

    @Test
    void storeLookupHistoryRecordsAFailureAndAnEmptyResult() {
        StoreIntent intent = new StoreIntent(StoreIntent.Type.PHONE, null, null, List.of(), false, null, null, null);

        Map<String, Object> failed = StoreLookupSpanHistory.of(intent, false,
                StoreLookup.failed(new IllegalStateException("DB 오류")));
        Map<String, Object> empty = StoreLookupSpanHistory.of(intent, false, new StoreLookup("<store_result/>", null));

        assertThat(failed).containsEntry("location_provided", false).containsEntry("found", 0)
                .containsEntry("error", "IllegalStateException: DB 오류");
        assertThat(empty).containsEntry("found", 0).containsEntry("error", null).doesNotContainKey("map_type");
    }
}
