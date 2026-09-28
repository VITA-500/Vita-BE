package com.vita.faq.controller;

import com.vita.auth.security.UserPrincipal;
import com.vita.common.page.PageRequest;
import com.vita.common.page.PageResponse;
import com.vita.faq.dto.*;
import com.vita.faq.service.AdminFaqService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 관리자용 FAQ 목록 조회, 등록, 수정 및 비활성화 API를 제공 */
@RestController
@RequestMapping("/admin/faqs")
public class AdminFaqController {
    private final AdminFaqService service;

    public AdminFaqController(AdminFaqService service) { this.service = service; }

    @GetMapping
    public PageResponse<FaqItemResponse> list(@RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer size, @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String category, @RequestParam(required = false) String status) {
        return service.list(PageRequest.of(page, size, keyword, null), category, status);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FaqItemResponse create(@Valid @RequestBody FaqCreateRequest request, @AuthenticationPrincipal UserPrincipal principal) {
        return service.create(request, principal.getUserId());
    }

    @PatchMapping("/{faqId}")
    public FaqUpdateResponse update(@PathVariable long faqId, @RequestBody FaqUpdateRequest request,
        @AuthenticationPrincipal UserPrincipal principal) {
        return service.update(faqId, request, principal.getUserId());
    }

    @DeleteMapping("/{faqId}")
    public FaqDeleteResponse delete(@PathVariable long faqId, @AuthenticationPrincipal UserPrincipal principal) {
        return service.delete(faqId, principal.getUserId());
    }
}
