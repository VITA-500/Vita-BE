package com.vita.chat.service;

import com.vita.chat.FallbackType;

/** 번역 실패를 에러로 취급하는 호출자(translateOrThrow)용 예외. */
public class QuestionTranslationException extends RuntimeException {

    private final FallbackType type;

    public QuestionTranslationException(FallbackType type, String reason) {
        super("질문 번역 실패 - type=" + type + ", reason=" + reason);
        this.type = type;
    }

    public FallbackType type() {
        return type;
    }
}