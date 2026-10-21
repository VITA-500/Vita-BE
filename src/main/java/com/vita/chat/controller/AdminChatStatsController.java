package com.vita.chat.controller;

import com.vita.chat.dto.ChatDismissResponse;
import org.springframework.web.bind.annotation.*;

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
    public ChatStatsResponse chat(@RequestParam(required = false) Integer limit,
                                  @RequestParam(required = false) String reason) {
        return chatStatsService.getUnresolvedStats(limit, reason);
    }

    /** 목록의 한 항목에 들어 있는 자동 감지와 (아쉬워요)를 모두 무시 처리 */
    @PatchMapping("/chat/{id}/dismiss")
    public ChatDismissResponse dismiss(@PathVariable Long id) {
        return chatStatsService.dismiss(id);
    }
}