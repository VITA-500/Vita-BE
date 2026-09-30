package com.vita.store.dto.response;

import java.util.List;

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
