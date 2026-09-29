package com.vita.store.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import com.vita.store.dto.response.RoutePoint;
import com.vita.store.dto.response.RouteResponse;
import com.vita.store.exception.RouteNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class DirectionService {

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final String STEP_WALKING = "WALKING";
    private static final String STEP_SUBWAY = "SUBWAY";
    private static final double MIN_WALK_REFINE_METERS = 30;

    private final RestClient restClient;
    private final String kakaoApiKey;

    public DirectionService(@Value("${kakao.rest-api-key}") String kakaoApiKey){
        this.kakaoApiKey = kakaoApiKey;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MS);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
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
        JsonNode routes = callKakao(url).path("routes");
        if(routes.isEmpty() || routes.get(0).path("result_code").asInt(-1) != 0) {
            throw new RouteNotFoundException("car");
        }
        JsonNode route = routes.get(0);
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
        if(!"OK".equals(root.path("status").asText())){
            throw new RouteNotFoundException(mode);
        }
        JsonNode route = root.path("route");
        JsonNode routeProps = route.path("properties");

        List<RoutePoint> path = new ArrayList<>();
        for(JsonNode leg : route.path("legs")){
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
        JsonNode routes = callKakao(url).path("routes");
        if(routes.isEmpty() || routes.get(0).path("steps").isEmpty()){
            throw new RouteNotFoundException("transit");
        }
        JsonNode firstRoute = routes.get(0);
        JsonNode props = firstRoute.path("properties");
        JsonNode steps = firstRoute.path("steps");

        List<List<RoutePoint>> stepPaths = new ArrayList<>();
        for(int i = 0; i < steps.size(); i++) {
            List<RoutePoint> stepPath = toPoints(steps.get(i).path("path").path("points"));

            if(STEP_WALKING.equals(stepType(steps.get(i))) &&
            !isInStationTransfer(steps, i) && stepPath.size() >= 2){
                stepPath = walkPath(stepPath.get(0), stepPath.get(stepPath.size() - 1));
            }
            stepPaths.add(stepPath);
        }

        RoutePoint origin = new RoutePoint(fromLat.doubleValue(), fromLng.doubleValue());
        RoutePoint destination = new RoutePoint(toLat.doubleValue(), toLng.doubleValue());
        List<RoutePoint> firstStep = stepPaths.get(0);
        List<RoutePoint> lastStep = stepPaths.get(stepPaths.size() - 1);
        List<RoutePoint> path = new ArrayList<>();
        if(!firstStep.isEmpty()) path.addAll(walkPath(origin, firstStep.get(0)));
        stepPaths.forEach(path::addAll);
        if(!lastStep.isEmpty()) path.addAll(walkPath(lastStep.get(lastStep.size() - 1), destination));

        return new RouteResponse("transit", props.path("totalDistance").asInt(), props.path("totalTime").asInt(), path);
    }

    private static String stepType(JsonNode step){
        return step.path("properties").path("type").asText();
    }

    private static boolean isInStationTransfer(JsonNode steps, int i){
        return i > 0 && i < steps.size() - 1
                && STEP_SUBWAY.equals(stepType(steps.get(i - 1)))
                && STEP_SUBWAY.equals(stepType(steps.get(i + 1)));
    }

    private List<RoutePoint> walkPath(RoutePoint from, RoutePoint to){
        List<RoutePoint> straight = List.of(from, to);
        if(distanceMeters(from, to) < MIN_WALK_REFINE_METERS){
            return straight;
        }
        try{
            List<RoutePoint> walk = findMapRoute("walk",
                    BigDecimal.valueOf(from.lat()), BigDecimal.valueOf(from.lng()),
                    BigDecimal.valueOf(to.lat()), BigDecimal.valueOf(to.lng())).path();
            if(walk.isEmpty()) return straight;

            List<RoutePoint> result = new ArrayList<>();
            result.add(from);
            result.addAll(walk);
            result.add(to);
            return result;
        } catch(BusinessException exception){
            log.warn("대중교통 도보 구간 보정 실패, 직선으로 대체: {}", exception.getMessage());
            return straight;
        }
    }

    private static double distanceMeters(RoutePoint a, RoutePoint b){
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLng = Math.toRadians(b.lng() - a.lng());
        double h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat())) * Math.pow(Math.sin(dLng / 2), 2);
        return 2 * 6_371_000 * Math.asin(Math.sqrt(h));
    }

    private JsonNode callKakao(String url) {
        try {
            return restClient.get()
                    .uri(url)
                    .header("Authorization", "KakaoAK " + kakaoApiKey)
                    .retrieve()
                    .body(JsonNode.class);
        } catch(RestClientException exception){
            log.error("카카오 길찾기 API 호출 실패: {}", url.split("\\?")[0], exception);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "길찾기 서비스를 일시적으로 사용할 수 없습니다.");
        }
    }

    private static List<RoutePoint> toPoints(JsonNode points){
        List<RoutePoint> result = new ArrayList<>();
        for(JsonNode point : points){
            result.add(new RoutePoint(point.get(1).asDouble(), point.get(0).asDouble()));
        }
        return result;
    }
}
