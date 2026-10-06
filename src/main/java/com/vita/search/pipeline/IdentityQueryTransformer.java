package com.vita.search.pipeline;

import org.springframework.stereotype.Component;

/** 질문을 바꾸지 않는 기본 변환기. 현재 서비스와 Baseline(실험 A)이 쓴다. */
@Component(IdentityQueryTransformer.BEAN_NAME)
public class IdentityQueryTransformer implements QueryTransformer {

	/** 설정({@code search.pipeline.query-transformer} 등)에서 이 변환기를 가리킬 때 쓰는 빈 이름. */
	public static final String BEAN_NAME = "identityQueryTransformer";

	@Override
	public TransformedQuery transform(String query) {
		return TransformedQuery.unchanged(query);
	}
}
