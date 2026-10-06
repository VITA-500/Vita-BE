package com.vita.search.pipeline;

import com.vita.embedding.EmbeddingProvider;
import com.vita.search.service.PlanSearchService;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 검색 파이프라인 빈을 만든다. 질문 변환기와 FAQ 검색기는 설정에 적은 빈 이름으로 고른다(기본은 변환 없음 + 한국어 벡터 검색).
 * 다른 팀이 만든 구현체(영어 번역, 질문 재작성, 영어 컬럼·Hybrid 검색)는 {@code @Component("이름")}으로 등록한 뒤
 * {@code search.pipeline.query-transformer} / {@code search.pipeline.faq-retriever}에 그 이름을 적으면 서비스에 적용된다.
 */
@Configuration
public class RetrievalPipelineConfig {

	/**
	 * threshold, 가산점 등 후처리 설정. 값의 근거(실측 데이터와 선정 이유)는 application.yml의 각 항목 주석에 있다.
	 */
	@Bean
	public RetrievalSettings retrievalSettings(
			@Value("${retrieval.similarity-threshold:0.83}") double faqThreshold,
			@Value("${plan.retrieval.similarity-threshold:0.81}") double planThreshold,
			@Value("${retrieval.irrelevant-rule.enabled:true}") boolean irrelevantRuleEnabled,
			@Value("${retrieval.category-boost.bonus:0.01}") double categoryBoostBonus) {
		return new RetrievalSettings(faqThreshold, planThreshold, irrelevantRuleEnabled, categoryBoostBonus);
	}

	@Bean
	public RetrievalPipeline retrievalPipeline(
			EmbeddingProvider embeddingProvider,
			Map<String, QueryTransformer> queryTransformers,
			Map<String, FaqRetriever> faqRetrievers,
			PlanSearchService planSearchService,
			RetrievalSettings settings,
			@Value("${search.pipeline.query-transformer:" + IdentityQueryTransformer.BEAN_NAME + "}") String queryTransformerName,
			@Value("${search.pipeline.faq-retriever:" + VectorFaqRetriever.BEAN_NAME + "}") String faqRetrieverName) {
		return new RetrievalPipeline(
				embeddingProvider,
				pick(queryTransformers, queryTransformerName, "search.pipeline.query-transformer"),
				pick(faqRetrievers, faqRetrieverName, "search.pipeline.faq-retriever"),
				planSearchService,
				settings);
	}

	/**
	 * 빈 이름으로 구현체를 고른다. 이름이 틀렸을 때 조용히 기본값으로 넘어가면 다른 검색 방식으로 측정·서비스하는 줄 모르고 지나가므로,
	 * 사용 가능한 이름을 알려 주며 바로 실패시킨다.
	 */
	public static <T> T pick(Map<String, T> beans, String name, String propertyName) {
		T bean = beans.get(name);
		if (bean == null) {
			throw new IllegalStateException(propertyName + "에 지정한 '" + name + "' 빈이 없습니다. 사용 가능한 이름: " + beans.keySet());
		}
		return bean;
	}
}
