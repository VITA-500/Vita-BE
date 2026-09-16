package com.vita.store.controller;

import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import com.vita.store.dto.response.StoreNearbyListResponse;
import com.vita.store.dto.response.StoreNearestResponse;
import com.vita.store.service.StoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequiredArgsConstructor
public class StoreController {

    private final StoreService storeService;

    @GetMapping("/stores/nearest")
    public StoreNearestResponse nearest(
            @RequestParam(required = false) BigDecimal lat,
            @RequestParam(required = false) BigDecimal lng){
        requireLocation(lat, lng);
        return storeService.findNearest(lat, lng);
    }

    @GetMapping("/stores/nearby")
    public StoreNearbyListResponse nearby(
            @RequestParam(required = false) BigDecimal lat,
            @RequestParam(required = false) BigDecimal lng,
            @RequestParam(required = false) Double radius){
        requireLocation(lat, lng);
        if(radius == null){
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "반경(radius)은 필수입니다.");
        }
        return storeService.findNearby(lat, lng, radius);
    }

    private void requireLocation(BigDecimal lat, BigDecimal lng){
        if(lat == null || lng == null){
            throw new BusinessException(ErrorCode.LOCATION_REQUIRED);
        }
    }
}
