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

/** 매장 조회, 길찾기, 예약 (사용자용) */
@RestController
@RequiredArgsConstructor
public class StoreController {

    private static final BigDecimal MAX_LAT = BigDecimal.valueOf(90);
    private static final BigDecimal MAX_LNG = BigDecimal.valueOf(180);

    private final StoreService storeService;
    private final DirectionService directionService;
    private final ReservationService reservationService;

    /** 가장 가까운 통신 매장 */
    @GetMapping("/stores/nearest")
    public StoreNearestResponse nearest(
            @RequestParam(required = false) BigDecimal lat,
            @RequestParam(required = false) BigDecimal lng){
        requireLocation(lat, lng);
        return storeService.findNearest(lat, lng);
    }

    /** 반경 안 매장. storeType=PARTNER면 제휴 매장 (업종 및 브랜드 필터 가능) */
    @GetMapping("/stores/nearby")
    public StoreNearbyListResponse nearby(
            @RequestParam(required = false) BigDecimal lat,
            @RequestParam(required = false) BigDecimal lng,
            @RequestParam(required = false) Double radius,
            @RequestParam(required = false) String storeType,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Long benefitId,
            @RequestParam(required = false) Boolean openNow,
            @RequestParam(required = false) String openAt){
        requireLocation(lat, lng);
        if(radius == null){
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "반경(radius)은 필수입니다.");
        }
        return storeService.findNearby(lat, lng, radius, storeType, category, benefitId, openNow, openAt);
    }

    @GetMapping("/stores/{storeId}")
    public StoreDetailResponse detail(@PathVariable Long storeId){
        return storeService.findById(storeId);
    }

    /** 길찾기. mode: car / walk / bicycle / transit */
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

    /** 방문 예약 (회원만, 통신 매장만) */
    @PostMapping("/stores/{storeId}/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse reserve(@PathVariable Long storeId,
                                       @Valid @RequestBody ReservationRequest request,
                                       @AuthenticationPrincipal UserPrincipal user){
        return reservationService.reserve(storeId, PrincipalUtils.userIdOf(user), request);
    }

    /** 위치는 필수, 범위를 벗어나면 400 (잘못된 좌표가 카카오까지 가서 오류가 나지 않게) */
    static void requireLocation(BigDecimal lat, BigDecimal lng){
        if(lat == null || lng == null){
            throw new BusinessException(ErrorCode.LOCATION_REQUIRED);
        }
        if(lat.abs().compareTo(MAX_LAT) > 0 || lng.abs().compareTo(MAX_LNG) > 0){
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "위치(lat, lng) 값이 올바르지 않습니다.");
        }
    }
}
