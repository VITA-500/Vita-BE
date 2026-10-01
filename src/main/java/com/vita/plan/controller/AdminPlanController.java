package com.vita.plan.controller;

import com.vita.common.page.PageRequest;
import com.vita.common.page.PageResponse;
import com.vita.plan.dto.PlanCreateRequest;
import com.vita.plan.dto.PlanDeleteResponse;
import com.vita.plan.dto.PlanItemResponse;
import com.vita.plan.dto.PlanUpdateRequest;
import com.vita.plan.dto.PlanUpdateResponse;
import com.vita.plan.service.AdminPlanService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 관리자용 요금제 목록 조회, 등록, 수정 및 비활성화 API를 제공한다. */
@RestController
@RequestMapping("/admin/plans")
public class AdminPlanController {

    private final AdminPlanService service;

    public AdminPlanController(AdminPlanService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<PlanItemResponse> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sortBy) {
        return service.list(PageRequest.of(page, size, keyword, sortBy));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlanItemResponse create(@Valid @RequestBody PlanCreateRequest request) {
        return service.create(request);
    }

    @PatchMapping("/{planId}")
    public PlanUpdateResponse update(
            @PathVariable long planId,
            @Valid @RequestBody PlanUpdateRequest request) {
        return service.update(planId, request);
    }

    @DeleteMapping("/{planId}")
    public PlanDeleteResponse delete(@PathVariable long planId) {
        return service.delete(planId);
    }
}
