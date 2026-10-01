package com.vita.store.dto.response;

import java.util.List;

public record RouteResponse(
        String mode,
        int distanceMeters,
        int durationSeconds,
        List<RoutePoint> path,
        List<RouteSegment> segments,
        List<RouteOption> routes){
}
