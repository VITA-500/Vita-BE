package com.vita.store.controller;

import com.vita.auth.security.UserPrincipal;
import com.vita.common.page.PageRequest;
import com.vita.common.page.PageResponse;
import com.vita.common.util.PrincipalUtils;
import com.vita.store.dto.request.BenefitRequest;
import com.vita.store.dto.response.AdminBenefitResponse;
import com.vita.store.dto.response.BenefitDeleteResponse;
import com.vita.store.service.BenefitService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/benefits")
@RequiredArgsConstructor
public class AdminBenefitController {

    private final BenefitService benefitService;

    @GetMapping
    public PageResponse<AdminBenefitResponse> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String category){
        return benefitService.searchForAdmin(PageRequest.of(page, size, keyword, sortBy), category);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminBenefitResponse create(@Valid @RequestBody BenefitRequest request,
                                       @AuthenticationPrincipal UserPrincipal admin){
        return benefitService.create(request, PrincipalUtils.userIdOf(admin));
    }

    @PatchMapping("/{benefitId}")
    public AdminBenefitResponse update(@PathVariable Long benefitId,
                                       @Valid @RequestBody BenefitRequest request,
                                       @AuthenticationPrincipal UserPrincipal admin){
        return benefitService.update(benefitId, request, PrincipalUtils.userIdOf(admin));
    }

    @DeleteMapping("/{benefitId}")
    public BenefitDeleteResponse delete(@PathVariable Long benefitId){
        return benefitService.delete(benefitId);
    }

}
