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
import java.util.regex.Pattern;
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
    // 목록은 가까운 일부라서 개수·"가장 ~한 매장"을 전체 기준으로 단정하지 않게 한다
    private static final String ON_MAP =
            "안내: 아래 매장 목록은 화면의 지도에도 함께 표시됨. 모든 매장은 요일과 상관없이 매일 같은 영업시간으로 영업함. " +
                    "목록은 조건에 맞는 매장 중 가까운 순 최대 " + LIMIT + "곳임. 사용자가 매장 수를 묻거나 " +
                    "'가장 먼', '가장 늦게까지'처럼 전체를 비교하는 질문을 했을 때만 가까운 " + LIMIT + "곳 기준이라는 점을 밝히고 " +
                    "전체 개수나 전체 중 1위처럼 단정하지 말 것. 그 외 질문에는 이 내용을 덧붙이지 말 것.\n";

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
                               List<String> otherPlaces, SingleStore single) {
        SearchPoint(BigDecimal lat, BigDecimal lng, String place, boolean named, String missedStore,
                    List<String> otherPlaces) {
            this(lat, lng, place, named, missedStore, otherPlaces, null);
        }

        boolean located() {
            return lat != null && lng != null;
        }
    }

    /**
     * 매장 하나만 물었을 때 ("역삼역 7번출구 1호점까지 길 알려줘", "1호점 영업시간")
     * 지도에는 그 매장만, 기준점은 사용자 위치로 보낸다 (매장 위치를 "내 위치"로 그리지 않게)
     *
     * @param storeId 물은 매장
     * @param userLat 사용자 위치 (없으면 null, 거리도 알 수 없음)
     */
    private record SingleStore(Long storeId, BigDecimal userLat, BigDecimal userLng) {
        boolean userLocated() {
            return userLat != null && userLng != null;
        }
    }

    // "근처 다른 매장", "주변", "1호점 말고"처럼 그 매장 주변을 묻는 말
    private static final Pattern NEARBY_WORDS = Pattern.compile("근처|주변|가까운|가까이|인근|부근|다른|말고");

    public StoreLookup find(StoreIntent intent, BigDecimal lat, BigDecimal lng) {
        return find(intent, lat, lng, null);
    }

    /**
     * @param question 사용자 질문 원문. 매장 이름으로 물었을 때 그 매장만 볼지, 주변까지 볼지 정하는 데 쓴다
     *                 (null이면 주변까지)
     */
    public StoreLookup find(StoreIntent intent, BigDecimal lat, BigDecimal lng, String question) {
        if(intent.isNone()) {
            return StoreLookup.empty();
        }
        try {
            return intent.type() == StoreIntent.Type.PARTNER
                    ? findPartnerStores(intent, lat, lng, question)
                    : findPhoneStores(intent, searchPoint(intent, lat, lng, question));
        } catch(Exception e) {
            log.warn("매장 조회 실패, 매장 정보 없이 답변 - intent={}", intent, e);
            return StoreLookup.empty();
        }
    }

    // 매장 이름이 있으면 그 매장 위치, 장소가 있으면 그 장소 ("방학역 근처" → 방학역), 둘 다 없으면 사용자 위치
    private SearchPoint searchPoint(StoreIntent intent, BigDecimal lat, BigDecimal lng, String question) {
        String missedStore = null;
        List<String> others = intent.otherPlaces();
        // "1호점"처럼 번호만 있는 이름은 매장을 특정할 수 없어 찾지 않는다 (아무 1호점이나 잡히지 않게)
        if (intent.storeName() != null && isVagueStoreName(intent.storeName())) {
            missedStore = intent.storeName();
        } else if (intent.storeName() != null) {
            Optional<StorePointResponse> store = storeService.findByNameForChat(intent.storeName());
            log.info("매장 이름 검색 - storeName={}, found={}", intent.storeName(), store.map(StorePointResponse::name));
            if (store.isPresent()) {
                return storePoint(store.get(), intent, lat, lng, question);
            }
            missedStore = intent.storeName();
        }
        if (intent.place() == null) {
            return new SearchPoint(lat, lng, null, false, missedStore, others);
        }
        // 매장 이름이 장소 칸으로 오기도 한다 ("8번출구점 주변 매장"). "점"으로 끝나면 우리 매장 이름으로 먼저 찾는다
        // ("강남역 2번 출구"처럼 "점"이 없는 장소는 지도 검색)
        if (intent.storeName() == null && intent.place().strip().endsWith("점") && !isVagueStoreName(intent.place())) {
            Optional<StorePointResponse> store = storeService.findByNameForChat(intent.place());
            log.info("장소 칸의 매장 이름 검색 - place={}, found={}", intent.place(), store.map(StorePointResponse::name));
            if (store.isPresent()) {
                return storePoint(store.get(), intent, lat, lng, question);
            }
        }
        String missed = missedStore;
        SearchPoint point = placeSearchService.find(intent.place())
                .map(place -> new SearchPoint(place.lat(), place.lng(), intent.place(), false, missed, others))
                .orElseGet(() -> new SearchPoint(null, null, intent.place(), false, missed, others));
        log.info("매장 기준 장소 - place={}, found={}", intent.place(), point.located());
        return point;
    }

    // 이름으로 찾은 매장을 기준점으로
    private static SearchPoint storePoint(StorePointResponse store, StoreIntent intent, BigDecimal lat, BigDecimal lng,
                                          String question) {
        // 거리 조건이 있으면 그 매장은 기준점으로만 쓴다 ("8번출구점에서 1km 이상 떨어진 매장")
        boolean named = intent.minKm() == null && intent.maxKm() == null;
        // 주변을 묻지 않았으면 그 매장만 사용자 위치와 함께 보여 준다
        SingleStore single = named && !asksNearby(question) ? new SingleStore(store.storeId(), lat, lng) : null;
        return new SearchPoint(store.lat(), store.lng(), store.name(), named, null, intent.otherPlaces(), single);
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
        if (point.missedStore() == null) {
            return "";
        }
        String name = PromptEscaper.escape(point.missedStore());
        if (isVagueStoreName(point.missedStore())) {
            return "안내: 사용자가 말한 \"" + name + "\"은 같은 이름의 매장이 여러 곳이라 특정할 수 없음. " +
                    "역이나 동네 이름과 함께 물어봐 달라고 먼저 안내할 것.\n";
        }
        return "안내: 사용자가 말한 매장 \"" + name + "\"은 매장 목록에 없음. 이 사실을 먼저 안내할 것.\n";
    }

    // 비타·번호·호점·출구·점을 빼고 남는 글자가 없으면 매장을 특정할 수 없는 이름 ("1호점", "3번출구점")
    private static boolean isVagueStoreName(String name) {
        return name.replaceAll("\\s+|비타|\\d+|호점|번출구|출구|번|점", "").isEmpty();
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
        PhoneSearch found = searchPhoneStores(point, services, time, minKm, maxKm);
        log.info("통신 매장 조회 - storeIntent={}, count={}", intent, found.stores().size());
        if (point.single() != null) {
            Optional<StoreChatItemResponse> asked = found.stores().stream()
                    .filter(s -> s.storeId().equals(point.single().storeId())).findFirst();
            if (asked.isPresent()) {
                return singlePhoneStore(intent, point, asked.get());
            }
        }

        String note = placeNote(point) + distanceNote(minKm, maxKm);
        // 그 시각에 영업하는 매장이 없으면 시간 조건 없이 다시 찾아 영업시간과 함께 안내한다 ("아침 8시에 갈 수 있는 매장")
        if(found.stores().isEmpty() && time != null){
            PhoneSearch anyTime = searchPhoneStores(point, services, null, minKm, maxKm);
            if(!anyTime.stores().isEmpty()){
                found = anyTime;
                note += closedAtNote(intent, "통신");
            }
        }
        if(found.stores().isEmpty()){
            String condition = minKm != null || maxKm != null ? "거리 조건(" + distanceText(minKm, maxKm) + ")에" : "조건에";
            return new StoreLookup(result(note + "확인 결과: " + condition +
                    " 맞는 통신 매장이 없음. 이 사실을 그대로 안내할 것.\n"), null);
        }
        List<StoreChatItemResponse> stores = found.stores();
        String xml = stores.stream()
                .map(this::toPhoneStoreXml).collect(Collectors.joining());
        return new StoreLookup(result(note + found.farNote() + ON_MAP + xml),
                ChatStoreMap.ofPhoneStores(stores, intent.services(), point.lat(), point.lng(), point.place()));
    }

    /** @param farNote 5km 안에 없어 더 넓게 찾았을 때의 안내 (아니면 빈 문자열) */
    private record PhoneSearch(List<StoreChatItemResponse> stores, String farNote) {
    }

    // 최대 거리를 말했으면 그 반경, 최소 거리만 말했으면 최대 범위(100km)에서 가까운 순
    // 거리 조건이 없고 5km 안에 없으면 100km까지 넓혀 가장 가까운 매장을 찾는다 (매장 데이터가 서울 근처뿐이라 그 밖에서 물을 때)
    private PhoneSearch searchPhoneStores(SearchPoint point, List<String> services, LocalTime time,
                                          Double minKm, Double maxKm) {
        double radiusKm = maxKm != null ? maxKm : (minKm != null ? PHONE_STORE_FALLBACK_RADIUS_KM : PHONE_STORE_RADIUS_KM);
        List<StoreChatItemResponse> stores = storeService.findNearbyForChat(
                point.lat(), point.lng(), radiusKm, minKm, LIMIT, services, time);
        // 사용자가 거리를 직접 정했으면 범위를 넓히지 않는다
        if (!stores.isEmpty() || minKm != null || maxKm != null) {
            return new PhoneSearch(stores, "");
        }
        stores = storeService.findNearbyForChat(
                point.lat(), point.lng(), PHONE_STORE_FALLBACK_RADIUS_KM, LIMIT, services, time);
        log.info("통신 매장 5km 밖 조회 - count={}", stores.size());
        return new PhoneSearch(stores, stores.isEmpty() ? "" :
                "확인 결과: 반경 5km 안에는 조건에 맞는 통신 매장이 없음. 아래는 그보다 먼 곳 중 가장 가까운 매장이니 " +
                        "5km 안에는 없다는 점과 거리를 함께 안내할 것.\n");
    }

    // 시간 조건을 빼고 다시 찾았을 때 안내 (그 시각에 연다고 오해하지 않게)
    private static String closedAtNote(StoreIntent intent, String storeKind) {
        String when = intent.openNow() ? "지금" : "요청한 시각(" + intent.openAt().format(HH_MM) + ")에";
        return "확인 결과: " + when + " 영업하는 " + storeKind + " 매장은 없음. 아래는 시간 조건 없이 찾은 가까운 매장이니, " +
                "그 시각에는 영업하지 않는다는 점과 각 매장의 영업시간을 함께 안내할 것.\n";
    }

    // 물은 통신 매장 하나만. 거리와 지도 기준점은 사용자 위치
    private StoreLookup singlePhoneStore(StoreIntent intent, SearchPoint point, StoreChatItemResponse s) {
        SingleStore single = point.single();
        StoreChatItemResponse store = new StoreChatItemResponse(s.storeId(), s.name(), s.address(), s.phone(),
                s.lat(), s.lng(), distanceFromUser(single, s.lat(), s.lng()), s.businessHours(), s.openNow(),
                s.consultServices(), s.providedServices());
        return new StoreLookup(result(singleNote(point) + toPhoneStoreXml(store)),
                ChatStoreMap.ofPhoneStores(List.of(store), intent.services(), single.userLat(), single.userLng(), null));
    }

    // 물은 제휴 매장 하나만. 거리와 지도 기준점은 사용자 위치
    private StoreLookup singlePartnerStore(String category, Long benefitId, String benefitXml, SearchPoint point,
                                           BenefitStoreListResponse.Item s) {
        SingleStore single = point.single();
        BenefitStoreListResponse.Item store = new BenefitStoreListResponse.Item(s.storeId(), s.name(), s.address(),
                s.lat(), s.lng(), distanceFromUser(single, s.lat(), s.lng()), s.businessHours(), s.openNow(),
                s.benefitId(), s.brand(), s.category(), s.benefitName());
        return new StoreLookup(result(benefitXml + singleNote(point) + toPartnerStoreXml(store)),
                ChatStoreMap.ofPartnerStores(category, benefitId, List.of(store), single.userLat(), single.userLng(),
                        null));
    }

    // 매장 하나만 물었을 때 안내. 거리는 사용자 위치 기준이고, 위치가 없으면 거리를 말하지 않게 한다
    private static String singleNote(SearchPoint point) {
        String name = PromptEscaper.escape(point.place());
        return otherPlacesNote(point) +
                "안내: 사용자가 물은 매장은 \"" + name + "\"이며 화면의 지도에 이 매장만 표시됨. " +
                "모든 매장은 요일과 상관없이 매일 같은 영업시간으로 영업함. " +
                (point.single().userLocated()
                        ? "거리는 사용자의 현재 위치에서 이 매장까지의 직선거리임.\n"
                        : "사용자 위치 정보가 없어 이 매장까지의 거리는 알 수 없음. 거리를 말하지 말 것.\n");
    }

    // 주변을 묻는 말이 있으면 그 매장 주변 매장까지 보여 준다. 질문을 모르면 기존처럼 주변까지
    static boolean asksNearby(String question) {
        return question == null || NEARBY_WORDS.matcher(question).find();
    }

    // 사용자 위치에서 매장까지 직선거리(km). 위치가 없으면 null
    private static Double distanceFromUser(SingleStore single, BigDecimal lat, BigDecimal lng) {
        if (!single.userLocated()) {
            return null;
        }
        return haversineKm(single.userLat().doubleValue(), single.userLng().doubleValue(),
                lat.doubleValue(), lng.doubleValue());
    }

    // 하버사인 공식 (지구 반지름 6371km). 매장 조회 SQL과 같은 계산
    static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLng / 2), 2);
        return 2 * 6371 * Math.asin(Math.sqrt(a));
    }

    private static String distanceXml(Double km) {
        return km == null ? "알 수 없음" : "%.2fkm".formatted(km);
    }

    private StoreLookup findPartnerStores(StoreIntent intent, BigDecimal lat, BigDecimal lng, String question) {
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
        SearchPoint point = searchPoint(intent, lat, lng, question);
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
        if (point.single() != null) {
            Optional<BenefitStoreListResponse.Item> asked = stores.stream()
                    .filter(s -> s.storeId().equals(point.single().storeId())).findFirst();
            if (asked.isPresent()) {
                return singlePartnerStore(category, benefitId, xml.toString(), point, asked.get());
            }
        }

        xml.append(placeNote(point)).append(distanceNote(minKm, maxKm));
        // 그 시각에 영업하는 매장이 없으면 시간 조건 없이 다시 찾아 영업시간과 함께 안내한다
        if (stores.isEmpty() && time != null) {
            stores = benefitService.findStores(category, point.lat(), point.lng(), maxKm, minKm, LIMIT,
                    benefitId, null, null).stores();
            if (!stores.isEmpty()) {
                xml.append(closedAtNote(intent, "제휴"));
            }
        }
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
                <distance>%s</distance>
                <business_hours>%s</business_hours>
                <open_now>%s</open_now>
                <phone>%s</phone>
                <services>%s</services>
                </store>
                """.formatted(PromptEscaper.escape(s.name()), PromptEscaper.escape(s.address()), distanceXml(s.distanceKm()),
                PromptEscaper.escape(s.businessHours()), openText(s.openNow()),
                PromptEscaper.escape(s.phone()), PromptEscaper.escape(services));
    }

    private String toPartnerStoreXml(BenefitStoreListResponse.Item s) {
        return """
                <store>
                <name>%s</name>
                <brand>%s</brand>
                <address>%s</address>
                <distance>%s</distance>
                <business_hours>%s</business_hours>
                <open_now>%s</open_now>
                </store>
                """.formatted(PromptEscaper.escape(s.name()), PromptEscaper.escape(s.brand()),
                PromptEscaper.escape(s.address()), distanceXml(s.distanceKm()),
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