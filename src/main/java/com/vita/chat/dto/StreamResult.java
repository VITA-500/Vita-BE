package com.vita.chat.dto;

/**
 * 답변 스트리밍 호출이 끝난 뒤 돌려주는 호출 기록. trace/span 저장용이라 답변 내용과 화면 동작에는 쓰지 않는다.
 *
 * @param userPrompt       모델에 보낸 user 프롬프트(context, 대화 이력, 질문이 조립된 최종 문자열). 알 수 없으면 null
 * @param model            응답 메타데이터에 적힌 모델 이름. 없으면 null
 * @param promptTokens     입력 토큰 수. 스트림에 사용량이 없으면 null
 * @param completionTokens 출력 토큰 수. 스트림에 사용량이 없으면 null
 * @param totalTokens      전체 토큰 수. 스트림에 사용량이 없으면 null
 */
public record StreamResult(String userPrompt, String model, Long promptTokens, Long completionTokens,
        Long totalTokens) {

    /** 기록할 내용이 없는 구현(더미 등)이 돌려주는 값. */
    public static StreamResult none() {
        return new StreamResult(null, null, null, null, null);
    }
}
