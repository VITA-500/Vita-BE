package com.vita.store.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 지명 → 좌표 (카카오 장소 검색)
 * 채팅에서 "방학역 근처 매장"처럼 사용자 위치가 아닌 곳을 물을 때 기준점으로 쓴다.
 * 지하철역을 먼저 찾고 ("잠실" → 잠실역), 역이 아니면 일반 장소 1위 ("코엑스")
 */
@Slf4j
@Service
public class PlaceSearchService {

    private static final String KEYWORD_URL = "https://dapi.kakao.com/v2/local/search/keyword.json";
    private static final String SUBWAY_STATION = "SW8";
    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final int READ_TIMEOUT_MS = 3000;

    private final RestClient restClient;
    private final String kakaoApiKey;

    public PlaceSearchService(@Value("${kakao.rest-api-key}") String kakaoApiKey) {
        this.kakaoApiKey = kakaoApiKey;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MS);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    /** 찾은 장소. name은 카카오 장소 이름 (예: "방학역 1호선") */
    public record Place(String name, BigDecimal lat, BigDecimal lng) {
    }

    /** 못 찾거나 카카오 호출이 실패하면 빈 값 */
    public Optional<Place> find(String placeName) {
        if (placeName == null || placeName.isBlank()) {
            return Optional.empty();
        }
        String name = placeName.replaceAll("\\s+", "").replaceAll("역$", "");
        if (name.isEmpty()) {
            return Optional.empty();
        }
        try {
            return findStation(name).or(() -> first(search(placeName.trim(), null, 1)));
        } catch (RuntimeException e) {
            log.warn("장소 검색 실패 - place={}, reason={}", placeName, e.getMessage());
            return Optional.empty();
        }
    }

    // "이름역"을 역 분류로 검색해서, 역 이름이 그 이름으로 시작할 때만 인정한다 (코엑스 → 삼성역 같은 엉뚱한 역 방지)
    // "성수동"처럼 3글자 이상 "~동"은 동을 뗀 이름(성수역)도 본다. "목동"은 그대로 둔다
    private Optional<Place> findStation(String name) {
        List<String> bases = new ArrayList<>(List.of(name));
        if (name.length() >= 3 && name.endsWith("동")) {
            bases.add(name.substring(0, name.length() - 1));
        }
        JsonNode stations = search(name + "역", SUBWAY_STATION, 5);
        for (String base : bases) {
            Optional<JsonNode> station = pick(stations, base + "역").or(() -> pick(stations, base));
            if (station.isPresent()) {
                return station.map(PlaceSearchService::toPlace);
            }
        }
        return Optional.empty();
    }

    private static Optional<JsonNode> pick(JsonNode documents, String prefix) {
        for (JsonNode document : documents) {
            if (document.path("place_name").asText("").startsWith(prefix)) {
                return Optional.of(document);
            }
        }
        return Optional.empty();
    }

    private static Optional<Place> first(JsonNode documents) {
        return documents.isEmpty() ? Optional.empty() : Optional.of(toPlace(documents.get(0)));
    }

    private static Place toPlace(JsonNode document) {
        // 카카오 좌표: x = 경도, y = 위도
        return new Place(document.path("place_name").asText(),
                new BigDecimal(document.path("y").asText()), new BigDecimal(document.path("x").asText()));
    }

    private JsonNode search(String query, String categoryGroupCode, int size) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromHttpUrl(KEYWORD_URL)
                .queryParam("query", query)
                .queryParam("size", size);
        if (categoryGroupCode != null) {
            uri.queryParam("category_group_code", categoryGroupCode);
        }
        JsonNode body = restClient.get()
                .uri(uri.encode().build().toUri())
                .header("Authorization", "KakaoAK " + kakaoApiKey)
                .retrieve()
                .body(JsonNode.class);
        return body == null ? MissingNode.getInstance() : body.path("documents");
    }
}
