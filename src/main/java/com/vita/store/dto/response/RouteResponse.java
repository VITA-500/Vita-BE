package com.vita.store.dto.response;

import java.util.List;

/** 길찾기 응답 */
public record RouteResponse(
        String mode,
        int distanceMeters,
        int durationSeconds,
        List<RoutePoint> path,
        List<RouteSegment> segments,
        List<RouteOption> routes){
}
