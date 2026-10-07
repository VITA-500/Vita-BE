package com.vita.chat.service;

import org.springframework.stereotype.Component;
import com.vita.chat.dto.QueryTransformResult;
import com.vita.search.pipeline.TransformedQuery;
import lombok.RequiredArgsConstructor;

@Component("bedrockQueryTransformer")
@RequiredArgsConstructor
public class QueryTransformerAdapter implements com.vita.search.pipeline.QueryTransformer {

    private final QueryTransformer delegate; // 작성하신 클래스

    @Override
    public TransformedQuery transform(String query) {
        QueryTransformResult r = delegate.transform(query, null); // 평가는 단일 턴이라 history 없음
        String faq = r.faqQuery() != null ? r.faqQuery() : query;
        String plan = r.planQuery() != null ? r.planQuery() : query;
        return new TransformedQuery(query, faq, plan); // (원문, FAQ용, 요금제용)
    }
}