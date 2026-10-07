package com.vita.chat.service;

import org.springframework.stereotype.Component;
import com.vita.chat.dto.QueryTransformResult;
import com.vita.search.pipeline.TransformedQuery;
import lombok.RequiredArgsConstructor;

@Component("bedrockQueryTransformer")
@RequiredArgsConstructor
public class QueryTransformerAdapter implements com.vita.search.pipeline.QueryTransformer {

    private final LlmQueryTransformer delegate; // 작성하신 클래스

    @Override
    public TransformedQuery transform(String query) {
        // 평가는 단일 턴이라 history 없음
        LlmQueryTransformer.TransformOutcome outcome = delegate.transformWithStatus(query, null);
        QueryTransformResult r = outcome.result();
        // 비어 있는 쪽(null)은 원문으로 채워 검색이 끊기지 않게 한다. 채우기 전의 상태는 info에 남아 평가에서 폴백을 셀 수 있다.
        String faq = r.faqQuery() != null ? r.faqQuery() : query;
        String plan = r.planQuery() != null ? r.planQuery() : query;
        return new TransformedQuery(query, faq, plan, outcome.info()); // (원문, FAQ용, 요금제용, 변환 기록)
    }
}
