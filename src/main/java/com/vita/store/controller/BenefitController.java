package com.vita.store.controller;

import com.vita.store.dto.response.BenefitListResponse;
import com.vita.store.dto.response.BenefitStoreListResponse;
import com.vita.store.service.BenefitService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/** 제휴 혜택 조회 (사용자용) */
@RestController
@RequiredArgsConstructor
public class BenefitController {

    private final BenefitService benefitService;

    /** 혜택 목록. category로 업종 필터 */
    @GetMapping("/benefits/list")
    public BenefitListResponse list(@RequestParam(required = false) String category){
        return benefitService.findAll(category);
    }

    /** 업종별 제휴 매장 거리순. radius 생략 시 거리 제한 없음 */
    @GetMapping("/benefits/{category}/stores")
    public BenefitStoreListResponse stores(
            @PathVariable String category,
            @RequestParam(required = false) BigDecimal lat,
            @RequestParam(required = false) BigDecimal lng,
            @RequestParam(required = false) Double radius,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long benefitId,
            @RequestParam(required = false) Boolean openNow,
            @RequestParam(required = false) String openAt){
        StoreController.requireLocation(lat, lng);
        return benefitService.findStores(category, lat, lng, radius, limit, benefitId, openNow, openAt);
    }
}
