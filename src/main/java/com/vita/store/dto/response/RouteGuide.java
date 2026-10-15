package com.vita.store.dto.response;

/** 길 안내 한 줄 (예: 우회전, 좌회전) */
public record RouteGuide(
        String guidance,
        String name,
        int distanceMeters,
        double lat,
        double lng) { }
