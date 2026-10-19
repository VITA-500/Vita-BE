package com.vita.chat.service;

import java.util.List;
import java.util.Set;

import com.vita.search.dto.FaqRetrievalContext;
import com.vita.search.dto.FaqSimilarityResult;
import com.vita.search.pipeline.FaqSelection;
import com.vita.search.pipeline.RetrievalOptions;
import com.vita.search.pipeline.RetrievalResult;
import com.vita.search.pipeline.RetrievalSettings;
import com.vita.search.pipeline.RetrievalTrace;
import com.vita.search.pipeline.StageTimings;
import com.vita.search.pipeline.TransformedQuery;
import com.vita.search.service.PlanSearchService.PlanSearchOutcome;

/** 스트리머 테스트가 검색 서비스 목이 돌려줄 검색 결과를 쉽게 만들도록 돕는다. */
final class TestRetrievalResults {

    private static final RetrievalSettings SETTINGS = new RetrievalSettings(0.83, 0.79, true, 0.01, 3, 20);

    private TestRetrievalResults() {
    }

    /** FAQ와 요금제를 모두 검색한 결과(FAQ 후보 1개, 요금제 후보 없음). 단계 시간은 모두 0이다. */
    static RetrievalResult searchedBoth(String original, String faqQuery, String planQuery) {
        return searchedBoth(original, faqQuery, planQuery, new StageTimings(0, 0, 0, 0, 0));
    }

    /** 단계별 시간을 정해서 만든 검색 결과. */
    static RetrievalResult searchedBoth(String original, String faqQuery, String planQuery, StageTimings timings) {
        FaqSimilarityResult candidate = new FaqSimilarityResult(7L, "해외로밍", "데이터 로밍", "FAQ 질문", "FAQ 답변", 0.9, null);
        FaqSelection faq = new FaqSelection(List.of(candidate), List.of(candidate), List.of(candidate), List.of(candidate), 0.9);
        RetrievalTrace trace = new RetrievalTrace(new float[] {0.1f}, new float[] {0.2f}, faqQuery != null, planQuery != null,
                false, SETTINGS, RetrievalOptions.forService(10));
        return new RetrievalResult(new TransformedQuery(original, faqQuery, planQuery), faq,
                new PlanSearchOutcome(List.of(), false), List.of(), 0.0, 0.9, Set.of(), timings,
                FaqRetrievalContext.empty(), trace);
    }

    /** 기록용 정보(설정)가 비어 있어서 단계 기록을 만들 때 예외가 나는 검색 결과. 답변에 쓰는 값(context)은 정상이다. */
    static RetrievalResult withBrokenTraceInfo(String original, String faqQuery, String planQuery) {
        RetrievalResult ok = searchedBoth(original, faqQuery, planQuery);
        RetrievalTrace broken = new RetrievalTrace(new float[] {0.1f}, new float[] {0.2f}, true, true, false, null,
                RetrievalOptions.forService(10));
        return new RetrievalResult(ok.query(), ok.faq(), ok.planOutcome(), ok.planResults(), ok.planTopSimilarity(),
                ok.topSimilarity(), ok.irrelevantRules(), ok.timings(), ok.context(), broken);
    }
}
