package com.vita.chat;

public enum ChatMessageStatus {
    PENDING,    // 생성중
    COMPLETED,  // 완료
    FAILED,     // 실패
    RETRYING    // 재시도중
}