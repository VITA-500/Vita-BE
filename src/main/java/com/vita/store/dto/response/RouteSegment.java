package com.vita.store.dto.response;

import java.util.List;

/** 지도에 그릴 경로 한 구간. 구간마다 색과 선 모양이 다르다 */
public record RouteSegment(
        String type,                    // WALKING / BUS / SUBWAY / BICYCLE / ROAD
        String lineName,                // 대중교통만: "2호선", "402"
        String vehicleType,             // 대중교통만: 버스 종류(간선, 지선, 일반 ...) / 지하철 종류(일반, 급행)
        String color,
        String style,                   // SOLID / DASHED(도보)
        String guidance,                // 안내 문구
        int distanceMeters,
        int durationSeconds,
        List<String> stops,             // 대중교통(버스·지하철)만: 정차역
        List<RoutePoint> path) { }
