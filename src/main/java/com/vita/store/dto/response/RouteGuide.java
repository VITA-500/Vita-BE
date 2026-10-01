package com.vita.store.dto.response;

public record RouteGuide(
        String guidance,
        String name,
        int distanceMeters,
        double lat,
        double lng) { }
