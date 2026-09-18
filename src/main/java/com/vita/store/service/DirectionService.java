package com.vita.store.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import com.vita.store.dto.response.RoutePoint;
import com.vita.store.dto.response.RouteResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class DirectionService {

    private final RestClient restClient;
    private final String kakaoApiKey;

    public DirectionService(@Value("${kakao.rest-api-key}") String kakaoApiKey){
        this.kakaoApiKey = kakaoApiKey;
        this.restClient = RestClient.create();
    }

    public RouteResponse findRoute(String mode, BigDecimal fromLat, BigDecimal fromLng, BigDecimal toLat, BigDecimal toLng){
        return switch(mode){
            case "car" -> findCarRoute(fromLat, fromLng, toLat, toLng);
            case "walk" -> findMapRoute("walk", fromLat, fromLng, toLat, toLng);
            case "bicycle" -> findMapRoute("bicycle", fromLat, fromLng, toLat, toLng);
            case "transit" -> findTransitRoute(fromLat, fromLng, toLat, toLng);
            default -> throw new BusinessException(ErrorCode.VALIDATION_ERROR, "지원하지 않는 이동수단입니다: " + mode);
        };
    }

    private RouteResponse findCarRoute(BigDecimal fromLat, BigDecimal fromLng, BigDecimal toLat, BigDecimal toLng){
        String url = "https://apis-navi.kakaomobility.com/v1/directions?origin=%s,%s&destination=%s,%s"
                .formatted(fromLng, fromLat, toLng, toLat);
        JsonNode route = callKakao(url).path("routes").get(0);
        JsonNode summary = route.path("summary");

        List<RoutePoint> path = new ArrayList<>();
        for(JsonNode section : route.path("sections")){
            for(JsonNode road : section.path("roads")){
                JsonNode vertexes = road.path("vertexes");
                for(int i = 0; i < vertexes.size(); i += 2){
                    path.add(new RoutePoint(vertexes.get(i + 1).asDouble(), vertexes.get(i).asDouble()));
                }
            }
        }
        return new RouteResponse("car", summary.path("distance").asInt(), summary.path("duration").asInt(), path);
    }

    private RouteResponse findMapRoute(String mode, BigDecimal fromLat, BigDecimal fromLng, BigDecimal toLat, BigDecimal toLng){
        String url = "https://dapi.kakao.com/v2/routing/%s?start_x=%s&start_y=%s&end_x=%s&end_y=%s"
                .formatted(mode, fromLng, fromLat, toLng, toLat);
        JsonNode root = callKakao(url);
        JsonNode routeProps = root.path("route").path("properties");

        List<RoutePoint> path = new ArrayList<>();
        for(JsonNode leg : root.path("route").path("legs")){
            for(JsonNode step : leg.path("steps")){
                for(JsonNode point : step.path("path").path("points")){
                    path.add(new RoutePoint(point.get(1).asDouble(), point.get(0).asDouble()));
                }
            }
        }
        return new RouteResponse(mode, routeProps.path("totalDistance").asInt(), routeProps.path("totalTime").asInt(), path);
    }

    private RouteResponse findTransitRoute(BigDecimal fromLat, BigDecimal fromLng, BigDecimal toLat, BigDecimal toLng){
        String url = "https://dapi.kakao.com/v2/routing/publictraffic?start_x=%s&start_y=%s&end_x=%s&end_y=%s"
                .formatted(fromLng, fromLat, toLng, toLat);
        JsonNode firstRoute = callKakao(url).path("routes").get(0);
        JsonNode props = firstRoute.path("properties");

        List<RoutePoint> path = new ArrayList<>();
        for(JsonNode step : firstRoute.path("steps")){
            for(JsonNode point : step.path("path").path("points")){
                path.add(new RoutePoint(point.get(1).asDouble(), point.get(0).asDouble()));
            }
        }
        return new RouteResponse("transit", props.path("totalDistance").asInt(), props.path("totalTime").asInt(), path);
    }

    private JsonNode callKakao(String url){
        return restClient.get()
                .uri(url)
                .header("Authorization", "KakaoAK " + kakaoApiKey)
                .retrieve()
                .body(JsonNode.class);
    }

}
