package com.vita.chat.service;

import com.vita.chat.dto.ChatStoreMap;
import com.vita.chat.dto.StoreIntent;
import com.vita.store.dto.response.BenefitResponse;
import com.vita.store.dto.response.BenefitStoreListResponse;
import com.vita.store.dto.response.StoreChatItemResponse;
import com.vita.store.dto.response.StorePointResponse;
import com.vita.store.entity.Benefit;
import com.vita.store.entity.BusinessHours;
import com.vita.store.service.BenefitService;
import com.vita.store.service.PlaceSearchService;
import com.vita.store.service.StoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatStoreFinder {

    private static final int LIMIT = 4;
    private static final double PHONE_STORE_RADIUS_KM = 5.0;
    // 5km 안에 없을 때, 또는 최소 거리만 물었을 때("1km 이상") 가장 가까운 매장을 찾는 범위
    private static final double PHONE_STORE_FALLBACK_RADIUS_KM = 100.0;
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    // 매장 데이터에 요일별 영업 정보가 없어 매일 같은 시간으로 본다 (LLM이 휴무일을 지어내지 않게)
    private static final String ON_MAP =
            "안내: 아래 매장 목록은 화면의 지도에도 함께 표시됨. 모든 매장은 요일과 상관없이 매일 같은 영업시간으로 영업함.\n";

    private static final String NO_LOCATION =
            "확인 결과: 사용자 위치 정보가 없어 주변 매장을 조회하지 못함. 위치 사용을 허용하면 " +
                    "가까운 매장을 안내할 수 있다고 안내할 것.\n";

    private final StoreService storeService;
    private final BenefitService benefitService;
    private final PlaceSearchService placeSearchService;

    /**
     * @param text      <store_result> 문자열
     * @param storeMap 매장 지도
     */
    public record StoreLookup(String text, ChatStoreMap storeMap) {
        public static StoreLookup empty() { return new StoreLookup("", null); }
    }

    /**
     * 매장을 찾을 기준점. 우선순위: 질문에 나온 매장 이름 > 장소 > 사용자 위치
     * 장소를 못 찾았거나 사용자 위치가 없으면 lat, lng가 null
     *
     * @param place       기준이 된 매장 이름이나 장소 이름 (사용자 위치 기준이면 null)
     * @param named       매장 이름으로 찾았는지 (그 매장이 목록 첫 번째)
     * @param missedStore 질문에 매장 이름이 있었는데 못 찾은 경우 그 이름
     * @param otherPlaces 질문에 나온 다른 장소 (한 번에 한 장소만 찾으므로 조회하지 않았다고 안내)
     */
    private record SearchPoint(BigDecimal lat, BigDecimal lng, String place, boolean named, String missedStore,
                               List<String> otherPlaces) {
        boolean located() {
            return lat != null && lng != null;
        }
    }

    public StoreLookup find(StoreIntent intent, BigDecimal lat, BigDecimal lng) {
        if(intent.isNone()) {
            return StoreLookup.empty();
        }
        try {
            return intent.type() == StoreIntent.Type.PARTNER
                    ? findPartnerStores(intent, lat, lng)
                    : findPhoneStores(intent, searchPoint(intent, lat, lng));
        } catch(Exception e) {
            log.warn("매장 조회 실패, 매장 정보 없이 답변 - intent={}", intent, e);
            return StoreLookup.empty();
        }
    }

    // 매장 이름이 있으면 그 매장 위치, 장소가 있으면 그 장소 ("방학역 근처" → 방학역), 둘 다 없으면 사용자 위치
    private SearchPoint searchPoint(StoreIntent intent, BigDecimal lat, BigDecimal lng) {
        String missedStore = null;
        List<String> others = intent.otherPlaces();
        if (intent.storeName() != null) {
            Optional<StorePointResponse> store = storeService.findByNameForChat(intent.storeName());
            log.info("매장 이름 검색 - storeName={}, found={}", intent.storeName(), store.map(StorePointResponse::name));
            if (store.isPresent()) {
                // 거리 조건이 있으면 그 매장은 기준점으로만 쓴다 ("8번출구점에서 1km 이상 떨어진 매장")
                boolean named = intent.minKm() == null && intent.maxKm() == null;
                return new SearchPoint(store.get().lat(), store.get().lng(), store.get().name(), named, null, others);
            }
            missedStore = intent.storeName();
        }
        if (intent.place() == null) {
            return new SearchPoint(lat, lng, null, false, missedStore, others);
        }
        String missed = missedStore;
        SearchPoint point = placeSearchService.find(intent.place())
                .map(place -> new SearchPoint(place.lat(), place.lng(), intent.place(), false, missed, others))
                .orElseGet(() -> new SearchPoint(null, null, intent.place(), false, missed, others));
        log.info("매장 기준 장소 - place={}, found={}", intent.place(), point.located());
        return point;
    }

    // 위치가 없을 때 안내. 장소를 못 찾은 것과 사용자 위치가 없는 것을 구분한다
    private static String noLocation(SearchPoint point) {
        if (point.place() == null) {
            return missedStoreNote(point) + NO_LOCATION;
        }
        return otherPlacesNote(point) + missedStoreNote(point) +
                "확인 결과: 사용자가 말한 장소 \"" + PromptEscaper.escape(point.place()) + "\"의 위치를 찾지 못함. " +
                "역 이름이나 동네 이름으로 다시 물어봐 달라고 안내할 것.\n";
    }

    // 기준점 안내. 매장 이름이면 그 매장이 첫 번째, 장소면 거리도 그 장소 기준 (사용자 위치 기준으로 오해하지 않게)
    private static String placeNote(SearchPoint point) {
        if (point.named()) {
            return otherPlacesNote(point) +
                    "안내: 사용자가 물은 매장은 \"" + PromptEscaper.escape(point.place()) + "\"이며 아래 목록의 첫 번째 매장임. " +
                    "나머지는 그 주변 매장이고 거리는 이 매장 기준.\n";
        }
        return otherPlacesNote(point) + missedStoreNote(point) + (point.place() == null ? "" :
                "안내: 아래 매장과 거리는 사용자가 말한 \"" + PromptEscaper.escape(point.place()) + "\" 기준임.\n");
    }

    // 장소가 여러 개면 첫 장소로만 찾았다고 알린다 (조회하지 않은 장소를 "매장 없음"으로 답하지 않게)
    private static String otherPlacesNote(SearchPoint point) {
        if (point.otherPlaces().isEmpty()) {
            return "";
        }
        return "안내: 질문에 나온 다른 장소 \"" + PromptEscaper.escape(String.join(", ", point.otherPlaces())) +
                "\"는 이번에 조회하지 않음. 한 번에 한 장소만 찾을 수 있으니 아래 결과가 어느 장소 기준인지 밝히고, " +
                "다른 장소는 따로 물어보면 찾아 준다고 안내할 것. 조회하지 않은 장소에 매장이 없다고 말하지 말 것.\n";
    }

    // 거리 조건을 적용해 찾았다고 알린다 (목록에 없는 거리의 매장을 "없다"고 추측하지 않게)
    private static String distanceNote(Double minKm, Double maxKm) {
        return minKm == null && maxKm == null ? "" :
                "안내: 사용자가 요청한 거리 조건(" + distanceText(minKm, maxKm) + ")을 적용해 찾은 결과임.\n";
    }

    private static String distanceText(Double minKm, Double maxKm) {
        if (minKm != null && maxKm != null) {
            return kmText(minKm) + " 이상 " + kmText(maxKm) + " 이내";
        }
        return minKm != null ? kmText(minKm) + " 이상" : kmText(maxKm) + " 이내";
    }

    // 1km 미만은 m로 (0.5 → 500m)
    private static String kmText(double km) {
        if (km < 1) {
            return Math.round(km * 1000) + "m";
        }
        return km == Math.floor(km) ? (long) km + "km" : km + "km";
    }

    private static String missedStoreNote(SearchPoint point) {
        return point.missedStore() == null ? "" :
                "안내: 사용자가 말한 매장 \"" + PromptEscaper.escape(point.missedStore()) + "\"은 매장 목록에 없음. " +
                        "이 사실을 먼저 안내할 것.\n";
    }

    private StoreLookup findPhoneStores(StoreIntent intent, SearchPoint point){
        if(!point.located()){
            return new StoreLookup(result(noLocation(point)), null);
        }
        // 매장 이름으로 물었으면 서비스·영업 조건으로 거르지 않는다 (그 매장이 첫 번째로 나와야 하므로)
        List<String> services = point.named() ? List.of() : intent.services();
        LocalTime time = point.named() ? null : filterTime(intent);
        // 거리 조건: 최대 거리를 말했으면 그 반경, 최소 거리만 말했으면 최대 범위(100km)에서 가까운 순
        Double minKm = point.named() ? null : intent.minKm();
        Double maxKm = point.named() ? null : intent.maxKm();
        boolean distanceAsked = minKm != null || maxKm != null;
        double radiusKm = maxKm != null ? maxKm : (minKm != null ? PHONE_STORE_FALLBACK_RADIUS_KM : PHONE_STORE_RADIUS_KM);
        List<StoreChatItemResponse> stores = storeService.findNearbyForChat(
                point.lat(), point.lng(), radiusKm, minKm, LIMIT, services, time);
        log.info("통신 매장 조회 - storeIntent={}, count={}", intent, stores.size());

        String note = placeNote(point) + distanceNote(minKm, maxKm);
        String farNote = "";
        if(stores.isEmpty()){
            // 사용자가 거리를 직접 정했으면 범위를 넓히지 않는다
            if(distanceAsked){
                return new StoreLookup(result(note + "확인 결과: 거리 조건(" + distanceText(minKm, maxKm) +
                        ")에 맞는 통신 매장이 없음. 이 사실을 그대로 안내할 것.\n"), null);
            }
            // 5km 안에 없으면 더 넓게 찾아 가장 가까운 매장을 안내한다 (매장 데이터가 서울 근처뿐이라 그 밖에서 물을 때)
            stores = storeService.findNearbyForChat(
                    point.lat(), point.lng(), PHONE_STORE_FALLBACK_RADIUS_KM, LIMIT, services, time);
            log.info("통신 매장 5km 밖 조회 - count={}", stores.size());
            if(stores.isEmpty()){
                return new StoreLookup(result(note +
                        "확인 결과: 조건에 맞는 통신 매장이 없음. 이 사실을 그대로 안내할 것.\n"), null);
            }
            farNote = "확인 결과: 반경 5km 안에는 조건에 맞는 통신 매장이 없음. 아래는 그보다 먼 곳 중 가장 가까운 매장이니 " +
                    "5km 안에는 없다는 점과 거리를 함께 안내할 것.\n";
        }
        String xml = stores.stream()
                .map(this::toPhoneStoreXml).collect(Collectors.joining());
        return new StoreLookup(result(note + farNote + ON_MAP + xml),
                ChatStoreMap.ofPhoneStores(stores, intent.services(), point.lat(), point.lng(), point.place()));
    }

    private StoreLookup findPartnerStores(StoreIntent intent, BigDecimal lat, BigDecimal lng) {
        Optional<BenefitResponse> brand = Optional.ofNullable(intent.brand()).flatMap(benefitService::findByBrand);
        String category = brand.map(BenefitResponse::category).orElse(validCategory(intent.category()));

        List<BenefitResponse> benefits = brand.map(List::of)
                .orElseGet(() -> benefitService.findAll(category).benefits());
        StringBuilder xml = new StringBuilder();
        benefits.forEach(benefit -> xml.append(toBenefitXml(benefit)));
        if (intent.brand() != null && brand.isEmpty()) {
            xml.append("안내: 사용자가 말한 \"").append(PromptEscaper.escape(intent.brand()))
                    .append("\"는 제휴 브랜드가 아님. 이 사실을 먼저 안내할 것.\n");
        }

        // 업종·브랜드·장소를 모두 모르면("제휴 혜택 뭐 있어") 혜택 목록만 안내
        if (category == null && intent.place() == null && intent.storeName() == null) {
            return new StoreLookup(result(xml.toString()), null);
        }
        // 장소를 말했으면 업종을 몰라도 그 장소 기준으로 모든 업종에서 찾는다 ("강남역 제휴 매장")
        SearchPoint point = searchPoint(intent, lat, lng);
        if (!point.located()) {
            return new StoreLookup(result(xml + noLocation(point)), null);
        }

        // 제휴 매장은 거리 조건이 없으면 거리 제한 없이 가까운 순이라 5km 밖이어도 나온다
        Long benefitId = brand.map(BenefitResponse::benefitId).orElse(null);
        LocalTime time = point.named() ? null : filterTime(intent);
        Double minKm = point.named() ? null : intent.minKm();
        Double maxKm = point.named() ? null : intent.maxKm();
        List<BenefitStoreListResponse.Item> stores =
                benefitService.findStores(category, point.lat(), point.lng(), maxKm, minKm, LIMIT,
                        benefitId, null, time == null ? null : time.format(HH_MM)).stores();
        log.info("제휴 매장 조회 - storeIntent={}, category={}, benefitId={}, count={}",
                intent, category, benefitId, stores.size());

        xml.append(placeNote(point)).append(distanceNote(minKm, maxKm));
        if (stores.isEmpty()) {
            return new StoreLookup(result(xml +
                    "확인 결과: 조건에 맞는 제휴 매장이 없음.\n"), null);
        }
        xml.append(ON_MAP);
        stores.forEach(store -> xml.append(toPartnerStoreXml(store)));
        return new StoreLookup(result(xml.toString()),
                ChatStoreMap.ofPartnerStores(category, benefitId, stores, point.lat(), point.lng(), point.place()));
    }

    /** 지금 영업 중이면 현재 시각, 특정 시각이면 그 시각 */
    private static LocalTime filterTime(StoreIntent intent){
        return intent.openNow() ? BusinessHours.nowInKorea() : intent.openAt();
    }

    private static String validCategory(String category) {
        return category != null && Benefit.CATEGORIES.contains(category) ? category : null;
    }

    private static String result(String body){
        return """
                <store_result>
                %s</store_result>
                """.formatted(body);
    }

    private String toPhoneStoreXml(StoreChatItemResponse s) {
        String services = String.join(", ", Stream.concat(s.consultServices().stream(),
                s.providedServices().stream()).toList());
        return """
                <store>
                <name>%s</name>
                <address>%s</address>
                <distance>%.2fkm</distance>
                <business_hours>%s</business_hours>
                <open_now>%s</open_now>
                <phone>%s</phone>
                <services>%s</services>
                </store>
                """.formatted(PromptEscaper.escape(s.name()), PromptEscaper.escape(s.address()), s.distanceKm(),
                PromptEscaper.escape(s.businessHours()), openText(s.openNow()),
                PromptEscaper.escape(s.phone()), PromptEscaper.escape(services));
    }

    private String toPartnerStoreXml(BenefitStoreListResponse.Item s) {
        return """
                <store>
                <name>%s</name>
                <brand>%s</brand>
                <address>%s</address>
                <distance>%.2fkm</distance>
                <business_hours>%s</business_hours>
                <open_now>%s</open_now>
                </store>
                """.formatted(PromptEscaper.escape(s.name()), PromptEscaper.escape(s.brand()),
                PromptEscaper.escape(s.address()), s.distanceKm(),
                PromptEscaper.escape(s.businessHours()), openText(s.openNow()));
    }

    private String toBenefitXml(BenefitResponse b) {
        return """
                <benefit>
                <brand>%s</brand>
                <category>%s</category>
                <name>%s</name>
                <description>%s</description>
                </benefit>
                """.formatted(PromptEscaper.escape(b.brand()), PromptEscaper.escape(b.category()),
                PromptEscaper.escape(b.name()), PromptEscaper.escape(b.description()));
    }

    private static String openText(Boolean openNow) {
        if(openNow == null) {
            return "정보 없음";
        }
        return openNow ? "영업 중" : "영업 종료";
    }
}