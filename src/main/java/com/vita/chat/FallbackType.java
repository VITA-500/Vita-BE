package com.vita.chat;

/** 폴백 사유 분류. 정상 번역이면 outcome의 fallbackType은 null. */
public enum FallbackType {
    INPUT,       // 빈 질문, 너무 긴 질문
    LLM_ERROR,   // Bedrock 호출 실패
    VALIDATION   // 숫자·요금제명 변형, 출력 과다 등 검증 실패
}