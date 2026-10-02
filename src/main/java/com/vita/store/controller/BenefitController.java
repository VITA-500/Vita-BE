package com.vita.store.controller;

import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import com.vita.store.dto.response.BenefitListResponse;
import com.vita.store.dto.response.BenefitStoreListResponse;
import com.vita.store.service.BenefitService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequiredArgsConstructor
public class BenefitController {

    private final BenefitService benefitService;

    @GetMapping("/benefits/list")
    public BenefitListResponse list(@RequestParam(required = false) String category){
        return benefitService.findAll(category);
    }

    @GetMapping("/benefits/{category}/stores")
    public BenefitStoreListResponse stores(
            @PathVariable String category,
            @RequestParam(required = false) BigDecimal lat,
            @RequestParam(required = false) BigDecimal lng,
            @RequestParam(required = false) Double radius,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long benefitId){
        if(lat == null || lng == null) {
            throw new BusinessException(ErrorCode.LOCATION_REQUIRED);
        }
        return benefitService.findStores(category, lat, lng, radius, limit, benefitId);
    }
}
