package com.vita.store.controller;

import com.vita.store.dto.response.BenefitListResponse;
import com.vita.store.service.BenefitService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class BenefitController {

    private final BenefitService benefitService;

    @GetMapping("/benefits/list")
    public BenefitListResponse list(@RequestParam(required = false) String category){
        return benefitService.findAll(category);
    }
}
