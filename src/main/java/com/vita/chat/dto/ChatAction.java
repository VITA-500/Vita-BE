package com.vita.chat.dto;

// 답변 아래 추천 버튼
public record ChatAction(String label, String href) {

    // 매장 지도 화면
    public static ChatAction storeMap() {
        return new ChatAction("가까운 매장 보기", "/chat?mode=store");
    }
}