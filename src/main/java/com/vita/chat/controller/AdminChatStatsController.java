package com.vita.chat.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.vita.chat.dto.ChatStatsResponse;
import com.vita.chat.service.ChatStatsService;

import lombok.RequiredArgsConstructor;

/** 관리자 대시보드 채팅 통계
 * /admin/** 는 관리자만 접근
 */
@RestController
@RequestMapping("/admin/stats")
@RequiredArgsConstructor
public class AdminChatStatsController {

    private final ChatStatsService chatStatsService;

    @GetMapping("/chat")
    public ChatStatsResponse chat(@RequestParam(required = false) Integer limit) {
        return chatStatsService.getUnresolvedStats(limit);
    }
}