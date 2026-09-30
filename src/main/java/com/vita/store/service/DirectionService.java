package com.vita.store.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import com.vita.store.dto.response.*;
import com.vita.store.exception.RouteNotFoundException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class DirectionService {

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final String STEP_WALKING = "WALKING";
    private static final String STEP_SUBWAY = "SUBWAY";
    private static final String SOLID = "SOLID";
    private static final String DASHED = "DASHED";
    private static final double MIN_WALK_REFINE_METERS = 30;
    private static final double WALK_SPEED_MPS = 1.1;           // 보정 실패로 직선을 쓸 때의 도보 시간 추정용 (약 4km/h)
    private static final int MAX_TRANSIT_OPTIONS = 3;

    private final RestClient restClient;
    private final String kakaoApiKey;
    // 도보 보정은 외부 API 응답 대기뿐이라 가상 스레드로 동시에 보낸다
    private final ExecutorService walkExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public DirectionService(@Value("${kakao.rest-api-key}") String kakaoApiKey){
        this.kakaoApiKey = kakaoApiKey;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MS);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    @PreDestroy
    void shutdown(){
        walkExecutor.shutdown();
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
        List<RouteGuide> guides = new ArrayList<>();
        for(JsonNode section : route.path("sections")){
            for(JsonNode road : section.path("roads")){
                path.addAll(toVertexPoints(road.path("vertexes")));
            }
            for(JsonNode guide : section.path("guides")){
                guides.add(new RouteGuide(guide.path("guidance").asText(), textOrNull(guide.path("name")),
                        guide.path("distance").asInt(), guide.path("y").asDouble(), guide.path("x").asDouble()));
            }
        }

        int distance = summary.path("distance").asInt();
        int duration = summary.path("duration").asInt();
        JsonNode fare = summary.path("fare");
        List<RouteSegment> segments = List.of(new RouteSegment("ROAD", null, null, RouteColors.CAR, SOLID, null,
                distance, duration, null, path));
        RouteOption option = new RouteOption(null, null, distance, duration, null, null,
                intOrNull(fare, "taxi"), intOrNull(fare, "toll"), path, segments, guides);
        return new RouteResponse("car", distance, duration, path, segments, List.of(option));
    }

    private RouteResponse findMapRoute(String mode, BigDecimal fromLat, BigDecimal fromLng, BigDecimal toLat, BigDecimal toLng) {
        String url = "https://dapi.kakao.com/v2/routing/%s?start_x=%s&start_y=%s&end_x=%s&end_y=%s"
                .formatted(mode, fromLng, fromLat, toLng, toLat);
        JsonNode root = callKakao(url);
        if (!"OK".equals(root.path("status").asText())) {
            throw new RouteNotFoundException(mode);
        }
        JsonNode route = root.path("route");
        JsonNode routeProps = route.path("properties");

        List<RoutePoint> path = new ArrayList<>();
        List<RouteGuide> guides = new ArrayList<>();
        for (JsonNode leg : route.path("legs")) {
            for (JsonNode step : leg.path("steps")) {
                path.addAll(toPairPoints(step.path("path").path("points")));
                JsonNode props = step.path("properties");
                guides.add(new RouteGuide(props.path("guidance").asText(), null, props.path("distance").asInt(),
                        props.path("y").asDouble(), props.path("x").asDouble()));
            }
        }

        int distance = routeProps.path("totalDistance").asInt();
        int duration = routeProps.path("totalTime").asInt();
        RouteSegment segment = "bicycle".equals(mode)
                ? new RouteSegment("BICYCLE", null, null, RouteColors.BICYCLE, SOLID,
                null, distance, duration, null, path)
                : new RouteSegment(STEP_WALKING, null, null, RouteColors.WALK, DASHED,
                null, distance, duration, null, path);
        List<RouteSegment> segments = List.of(segment);
        RouteOption option = new RouteOption(null, null, distance, duration, null,
                null, null, null, path, segments, guides);

        return new RouteResponse(mode, distance, duration, path, segments, List.of(option));
    }

    private RouteResponse findTransitRoute(BigDecimal fromLat, BigDecimal fromLng, BigDecimal toLat, BigDecimal toLng){
        String url = "https://dapi.kakao.com/v2/routing/publictraffic?start_x=%s&start_y=%s&end_x=%s&end_y=%s"
                .formatted(fromLng, fromLat, toLng, toLat);
        List<JsonNode> candidates = new ArrayList<>();
        for(JsonNode route : callKakao(url).path("routes")){
            if(firstPoint(route.path("steps")) != null){
                candidates.add(route);
            }
        }
        if(candidates.isEmpty()){
            throw new RouteNotFoundException("transit");
        }

        RoutePoint origin = new RoutePoint(fromLat.doubleValue(), fromLng.doubleValue());
        RoutePoint destination = new RoutePoint(toLat.doubleValue(), toLng.doubleValue());
        Map<Integer, List<String>> picked = pickTransitRoutes(candidates);

        // 후보들의 도보 구간을 모아 한 번에 병렬 보정한다. Set이라 후보끼리 겹치는 구간(같은 역 출발 등)은 한 번만 호출된다.
        Set<WalkLeg> legs = new LinkedHashSet<>();
        picked.keySet().forEach(index -> legs.addAll(walkLegs(candidates.get(index), origin, destination)));
        Map<WalkLeg, WalkResult> walks = refineWalks(legs);

        List<RouteOption> options = new ArrayList<>();
        picked.forEach((index, tags) -> options.add(toTransitOption(candidates.get(index), tags, origin, destination, walks)));

        RouteOption first = options.get(0);
        return new RouteResponse("transit", first.distanceMeters(), first.durationSeconds(),
                first.path(), first.segments(), options);
    }

    // 카카오 후보 중 추천(첫 번째) / 최단시간 / 최소환승을 고르고, 겹치면 태그를 합치고 모자라면 카카오 순서대로 채운다.
    private Map<Integer, List<String>> pickTransitRoutes(List<JsonNode> routes){
        int fastest = 0;
        int fewestTransfers = 0;
        for(int i = 1; i < routes.size(); i++){
            if(totalTime(routes.get(i)) < totalTime(routes.get(fastest))){
                fastest = i;
            }
            int transfers = transfers(routes.get(i));
            int best = transfers(routes.get(fewestTransfers));
            if(transfers < best || (transfers == best && totalTime(routes.get(i)) < totalTime(routes.get(fewestTransfers)))){
                fewestTransfers = i;
            }
        }

        Map<Integer, List<String>> picked = new LinkedHashMap<>();
        picked.computeIfAbsent(0, key -> new ArrayList<>()).add("추천");
        picked.computeIfAbsent(fastest, key -> new ArrayList<>()).add("최단시간");
        picked.computeIfAbsent(fewestTransfers, key -> new ArrayList<>()).add("최소환승");
        for(int i = 0; i < routes.size() && picked.size() < MAX_TRANSIT_OPTIONS; i++){
            if(!picked.containsKey(i)){
                picked.put(i, new ArrayList<>(List.of("대안")));
            }
        }
        return picked;
    }

    // 카카오 대중교통 응답에는 출발지→첫 정류장, 마지막 정류장→목적지 도보가 없어서 직접 보정 대상에 넣는다
    private List<WalkLeg> walkLegs(JsonNode route, RoutePoint origin, RoutePoint destination){
        JsonNode steps = route.path("steps");
        List<WalkLeg> legs = new ArrayList<>();
        legs.add(new WalkLeg(origin, firstPoint(steps)));
        for(int i = 0; i < steps.size(); i++){
            WalkLeg transfer = outdoorTransferLeg(steps, i);
            if(transfer != null){
                legs.add(transfer);
            }
        }
        legs.add(new WalkLeg(lastPoint(steps), destination));
        return legs;
    }

    // 역 밖을 걷는 환승만 보정 대상이다. 지하철↔지하철 환승은 역 내부 이동이라 지상 경로로 바꾸면 오히려 틀린다.
    private WalkLeg outdoorTransferLeg(JsonNode steps, int i){
        if(!STEP_WALKING.equals(stepType(steps.get(i))) || isInStationTransfer(steps, i)){
            return null;
        }
        List<RoutePoint> points = toPairPoints(steps.get(i).path("path").path("points"));
        return points.size() < 2 ? null : new WalkLeg(points.get(0), points.get(points.size() - 1));
    }

    private Map<WalkLeg, WalkResult> refineWalks(Collection<WalkLeg> legs){
        Map<WalkLeg, CompletableFuture<WalkResult>> futures = new LinkedHashMap<>();
        for(WalkLeg leg : legs){
            futures.put(leg, leg.distanceMeters() < MIN_WALK_REFINE_METERS
                    ? CompletableFuture.completedFuture(WalkResult.straight(leg))
                    : CompletableFuture.supplyAsync(() -> refineWalk(leg), walkExecutor));
        }
        Map<WalkLeg, WalkResult> results = new HashMap<>();
        futures.forEach((leg, future) -> results.put(leg, future.join()));
        return results;
    }

    private WalkResult refineWalk(WalkLeg leg){
        try{
            RouteResponse walk = findMapRoute("walk",
                    BigDecimal.valueOf(leg.from().lat()), BigDecimal.valueOf(leg.from().lng()),
                    BigDecimal.valueOf(leg.to().lat()), BigDecimal.valueOf(leg.to().lng()));
            if(walk.path().isEmpty()){
                return WalkResult.straight(leg);
            }
            // 도보 API는 가까운 보행 노드로 스냅되므로, 원래 지점과 이어지도록 양 끝을 붙인다
            List<RoutePoint> path = new ArrayList<>();
            path.add(leg.from());
            path.addAll(walk.path());
            path.add(leg.to());
            return new WalkResult(path, walk.distanceMeters(), walk.durationSeconds());
        } catch(RuntimeException exception){
            // 보조 기능이라 어떤 실패든 직선으로 대체한다. 병렬 실행 중 예외가 대중교통 응답 전체를 깨지 않게 넓게 잡는다.
            log.warn("대중교통 도보 구간 보정 실패, 직선으로 대체: {}", exception.getMessage());
            return WalkResult.straight(leg);
        }
    }

    private RouteOption toTransitOption(JsonNode route, List<String> tags, RoutePoint origin, RoutePoint destination,
                                        Map<WalkLeg, WalkResult> walks){
        JsonNode props = route.path("properties");
        JsonNode steps = route.path("steps");

        List<RouteSegment> segments = new ArrayList<>();
        addWalkSegment(segments, walks.get(new WalkLeg(origin, firstPoint(steps))));
        for(int i = 0; i < steps.size(); i++){
            segments.add(toTransitSegment(steps, i, walks));
        }
        addWalkSegment(segments, walks.get(new WalkLeg(lastPoint(steps), destination)));

        List<RoutePoint> path = new ArrayList<>();
        segments.forEach(segment -> path.addAll(segment.path()));
        return new RouteOption(tags, textOrNull(props.path("type")),
                props.path("totalDistance").asInt(), props.path("totalTime").asInt(),
                intOrNull(props.path("fare"), "value"), intOrNull(props, "transfers"),
                null, null, path, segments, null);
    }

    private RouteSegment toTransitSegment(JsonNode steps, int i, Map<WalkLeg, WalkResult> walks){
        JsonNode props = steps.get(i).path("properties");
        String type = props.path("type").asText("");
        String guidance = textOrNull(props.path("guidance"));
        int distance = props.path("distance").asInt();
        int duration = props.path("time").asInt();
        List<RoutePoint> path = toPairPoints(steps.get(i).path("path").path("points"));

        if(STEP_WALKING.equals(type)){
            WalkLeg transfer = outdoorTransferLeg(steps, i);
            if(transfer != null){
                path = walks.get(transfer).path();
            }
            return new RouteSegment(STEP_WALKING, null, null, RouteColors.WALK, DASHED,
                    guidance, distance, duration, null, path);
        }

        // 버스 여러 대가 같은 구간을 가면(예: "470외 1대") 첫 번째 차량 기준으로 표시한다
        JsonNode vehicle = props.path("vehicles").path(0);
        String lineName = textOrNull(vehicle.path("name"));
        String vehicleType = textOrNull(vehicle.path("type"));
        List<String> stops = new ArrayList<>();
        for(JsonNode stop : props.path("stops")){
            stops.add(stop.path("name").asText());
        }
        return new RouteSegment(type, lineName, vehicleType, RouteColors.transit(type, lineName, vehicleType), SOLID,
                guidance, distance, duration, stops, path);
    }

    private static void addWalkSegment(List<RouteSegment> segments, WalkResult walk){
        if(walk == null || walk.distanceMeters() < 1){
            return;
        }
        segments.add(new RouteSegment(STEP_WALKING, null, null, RouteColors.WALK, DASHED, null,
                walk.distanceMeters(), walk.durationSeconds(), null, walk.path()));
    }

    private static String stepType(JsonNode step){
        return step.path("properties").path("type").asText();
    }

    private static boolean isInStationTransfer(JsonNode steps, int i){
        return i > 0 && i < steps.size() - 1
                && STEP_SUBWAY.equals(stepType(steps.get(i - 1)))
                && STEP_SUBWAY.equals(stepType(steps.get(i + 1)));
    }

    private static RoutePoint firstPoint(JsonNode steps){
        if(steps.isEmpty()){
            return null;
        }
        List<RoutePoint> points = toPairPoints(steps.get(0).path("path").path("points"));
        return points.isEmpty() ? null : points.get(0);
    }

    private static RoutePoint lastPoint(JsonNode steps){
        if(steps.isEmpty()){
            return null;
        }
        List<RoutePoint> points = toPairPoints(steps.get(steps.size() - 1).path("path").path("points"));
        return points.isEmpty() ? null : points.get(points.size() - 1);
    }

    private static int totalTime(JsonNode route){
        return route.path("properties").path("totalTime").asInt();
    }

    private static int transfers(JsonNode route){
        return route.path("properties").path("transfers").asInt();
    }

    private static double distanceMeters(RoutePoint a, RoutePoint b){
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLng = Math.toRadians(b.lng() - a.lng());
        double h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat())) * Math.pow(Math.sin(dLng / 2), 2);
        return 2 * 6_371_000 * Math.asin(Math.sqrt(h));
    }

    // 카카오모빌리티 vertexes: [경도, 위도, 경도, 위도, ...]
    private static List<RoutePoint> toVertexPoints(JsonNode vertexes){
        List<RoutePoint> points = new ArrayList<>();
        for(int i = 0; i + 1 < vertexes.size(); i += 2){
            points.add(new RoutePoint(vertexes.get(i + 1).asDouble(), vertexes.get(i).asDouble()));
        }
        return points;
    }

    // 카카오맵 경로 points: [[경도, 위도], ...]
    private static List<RoutePoint> toPairPoints(JsonNode points){
        List<RoutePoint> result = new ArrayList<>();
        for(JsonNode point : points){
            result.add(new RoutePoint(point.get(1).asDouble(), point.get(0).asDouble()));
        }
        return result;
    }

    private static String textOrNull(JsonNode node){
        String text = node.asText("");
        return text.isBlank() ? null : text;
    }

    private static Integer intOrNull(JsonNode parent, String field){
        return parent.hasNonNull(field) ? parent.get(field).asInt() : null;
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

    private record WalkLeg(RoutePoint from, RoutePoint to){
        double distanceMeters(){
            return from == null || to == null ? 0 : DirectionService.distanceMeters(from, to);
        }
    }

    private record WalkResult(List<RoutePoint> path, int distanceMeters, int durationSeconds){
        static WalkResult straight(WalkLeg leg){
            if(leg.from() == null || leg.to() == null){
                return new WalkResult(List.of(), 0, 0);
            }
            double meters = leg.distanceMeters();
            return new WalkResult(List.of(leg.from(), leg.to()),
                    (int) Math.round(meters), (int) Math.round(meters / WALK_SPEED_MPS));
        }
    }
}