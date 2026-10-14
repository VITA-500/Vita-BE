package com.vita.chat.service;

import org.springframework.stereotype.Component;

import com.vita.chat.FallbackType;
import com.vita.search.pipeline.TransformInfo;
import com.vita.search.pipeline.TransformedQuery;

import lombok.RequiredArgsConstructor;

/**
 * 질문 번역 모듈({@link QuestionTranslator})을 검색 파이프라인의 질문 변환 자리에 끼우는 어댑터 (실험 B).
 * 번역문 하나를 FAQ 검색과 요금제 검색에 똑같이 쓴다 — 질문을 FAQ용/요금제용으로 나누는 Query Transformation은 하지 않는다.
 * 영어 임베딩을 검색하는 검색기({@code englishFaqRetriever}, {@code englishPlanRetriever})와 함께 쓴다.
 */
@Component(QuestionTranslatorAdapter.BEAN_NAME)
@RequiredArgsConstructor
public class QuestionTranslatorAdapter implements com.vita.search.pipeline.QueryTransformer {

    /** 설정({@code search.eval.query-transformer} 등)에서 이 변환기를 가리킬 때 쓰는 빈 이름. */
    public static final String BEAN_NAME = "translatingQueryTransformer";

    /** 폴백 사유의 접두어. 질문 변환(Query Transformation)의 폴백 사유와 평가 집계에서 구분된다. */
    static final String REASON_PREFIX = "TRANSLATION_";

    private final QuestionTranslator delegate;

    @Override
    public TransformedQuery transform(String query) {
        QuestionTranslator.TranslationOutcome outcome = delegate.translateWithStatus(query);
        if (outcome.fallback()) {
            // 번역 실패 시 번역기가 원문을 돌려주므로, 영어 임베딩을 한국어 원문으로 검색하게 된다. 평가에서 이 질문들을 셀 수 있게 사유를 남긴다.
            return new TransformedQuery(query, query, query, TransformInfo.fallback(reason(outcome.fallbackType())));
        }
        return new TransformedQuery(query, outcome.translated(), outcome.translated(), TransformInfo.changed());
    }

    /**
     * LLM 호출 실패는 일시적일 수 있어, 평가의 변환 결과 저장 파일이 저장하지 않고 다음 실행에서 다시 시도하는 사유(CALL_FAILED)로 넘긴다.
     * 입력 이상·출력 검증 실패는 다시 불러도 같은 결과일 가능성이 높아 번역 전용 사유로 남긴다.
     */
    private static String reason(FallbackType type) {
        if (type == FallbackType.LLM_ERROR) {
            return TransformInfo.REASON_CALL_FAILED;
        }
        return REASON_PREFIX + (type == null ? "UNKNOWN" : type.name());
    }
}
