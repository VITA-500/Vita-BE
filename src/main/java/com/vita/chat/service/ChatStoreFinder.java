package com.vita.chat.service;

import com.vita.chat.dto.StoreIntent;
import com.vita.chat.dto.StoresPreview;
import com.vita.store.dto.response.BenefitResponse;
import com.vita.store.dto.response.BenefitStoreListResponse;
import com.vita.store.dto.response.StoreChatItemResponse;
import com.vita.store.entity.Benefit;
import com.vita.store.entity.BusinessHours;
import com.vita.store.service.BenefitService;
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
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private static final String ON_MAP =
            "안내: 아래 매장 목록은 화면의 지도에도 함께 표시됨.\n";

    private static final String NO_LOCATION =
            "확인 결과: 사용자 위치 정보가 없어 주변 매장을 조회하지 못함. 위치 사용을 허용하면 " +
                    "가까운 매장을 안내할 수 있다고 안내할 것.\n";

    private final StoreService storeService;
    private final BenefitService benefitService;

    /**
     * @param text      <store_result> 문자열
     * @param preview   미니맵 데이터
     */
    public record StoreLookup(String text, StoresPreview preview) {
        public static StoreLookup empty() { return new StoreLookup("", null); }
    }

    public StoreLookup find(StoreIntent intent, BigDecimal lat, BigDecimal lng) {
        if(intent.isNone()) {
            return StoreLookup.empty();
        }
        try {
            return intent.type() == StoreIntent.Type.PARTNER
                    ? findPartnerStores(intent, lat, lng)
                    : findPhoneStores(intent, lat, lng);
        } catch(Exception e) {
            log.warn("매장 조회 실패, 매장 정보 없이 답변 - intent={}", intent, e);
            return StoreLookup.empty();
        }
    }

    private StoreLookup findPhoneStores(StoreIntent intent, BigDecimal lat, BigDecimal lng){
        if(lat == null || lng == null){
            return new StoreLookup(result(NO_LOCATION), null);
        }
        List<StoreChatItemResponse> stores = storeService.findNearbyForChat(
                lat, lng, PHONE_STORE_RADIUS_KM, LIMIT, intent.services(), filterTime(intent));
                log.info("통신 매장 조회 - storeIntent={}, count={}", intent, stores.size());

                if(stores.isEmpty()){
                    return new StoreLookup(result(
                            "확인 결과: 반경 5km 안에 조건에 맞는 통신 매장이 없음. 이 사실을 그대로 안내할 것."), null);
                }
                String xml = stores.stream()
                        .map(this::toPhoneStoreXml).collect(Collectors.joining());
                return new StoreLookup(result(ON_MAP + xml), StoresPreview.ofPhoneStores(stores));
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

        // 업종을 알 수 없으면 혜택 목록만 안내
        if (category == null) {
            return new StoreLookup(result(xml.toString()), null);
        }
        if (lat == null || lng == null) {
            return new StoreLookup(result(xml + NO_LOCATION), null);
        }

        Long benefitId = brand.map(BenefitResponse::benefitId).orElse(null);
        LocalTime time = filterTime(intent);
        List<BenefitStoreListResponse.Item> stores =
                benefitService.findStores(category, lat, lng, null, LIMIT,
                        benefitId, null, time == null ? null : time.format(HH_MM)).stores();
        log.info("제휴 매장 조회 - storeIntent={}, category={}, benefitId={}, count={}",
                intent, category, benefitId, stores.size());

        if (stores.isEmpty()) {
            return new StoreLookup(result(xml +
                    "확인 결과: 조건에 맞는 제휴 매장이 없음.\n"), null);
        }
        xml.append(ON_MAP);
        stores.forEach(store -> xml.append(toPartnerStoreXml(store)));
        return new StoreLookup(result(xml.toString()),
                StoresPreview.ofPartnerStores(category, benefitId, stores));
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