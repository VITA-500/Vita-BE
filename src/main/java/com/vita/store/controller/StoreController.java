package com.vita.store.controller;

import com.vita.auth.security.UserPrincipal;
import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import com.vita.common.util.PrincipalUtils;
import com.vita.store.dto.request.ReservationRequest;
import com.vita.store.dto.response.*;
import com.vita.store.service.DirectionService;
import com.vita.store.service.ReservationService;
import com.vita.store.service.StoreService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequiredArgsConstructor
public class StoreController {

    private final StoreService storeService;
    private final DirectionService directionService;
    private final ReservationService reservationService;

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
            @RequestParam(required = false) Double radius,
            @RequestParam(required = false) String storeType,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Long benefitId){
        requireLocation(lat, lng);
        if(radius == null){
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "반경(radius)은 필수입니다.");
        }
        return storeService.findNearby(lat, lng, radius, storeType, category, benefitId);
    }

    @GetMapping("/stores/{storeId}")
    public StoreDetailResponse detail(@PathVariable Long storeId){
        return storeService.findById(storeId);
    }

    @GetMapping("/stores/{storeId}/directions")
    public RouteResponse directions(
            @PathVariable Long storeId,
            @RequestParam(required = false) BigDecimal fromLat,
            @RequestParam(required = false) BigDecimal fromLng,
            @RequestParam(required = false) String mode){
        requireLocation(fromLat, fromLng);
        if(mode == null){
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "이동수단(mode)은 필수입니다.");
        }
        StoreDetailResponse store = storeService.findById(storeId);
        return directionService.findRoute(mode, fromLat, fromLng, store.lat(), store.lng());
    }

    @PostMapping("/stores/{storeId}/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse reserve(@PathVariable Long storeId,
                                       @Valid @RequestBody ReservationRequest request,
                                       @AuthenticationPrincipal UserPrincipal user){
        return reservationService.reserve(storeId, PrincipalUtils.userIdOf(user), request);
    }

    private void requireLocation(BigDecimal lat, BigDecimal lng){
        if(lat == null || lng == null){
            throw new BusinessException(ErrorCode.LOCATION_REQUIRED);
        }
    }
}
