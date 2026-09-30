package com.vita.store.dto.response;

import java.util.List;

public record RouteOption(
        List<String> tags,                  // 대중교통만: 추천 / 최단시간 / 최소환승 / 대안
        String routeType,                   // 대중교통만: BUS / SUBWAY / BUS_AND_SUBWAY
        int distanceMeters,
        int durationSeconds,
        Integer fare,                       // 요금
        Integer transfers,                  // 환승 횟수
        Integer taxiFare,                   // 예상 택시요금
        Integer toll,                       // 자동차 동행료
        List<RoutePoint> path,
        List<RouteSegment> segments,
        List<RouteGuide> guides) { }        // 자동차·도보·자전거 안내 목록 (대중교통은 null)
