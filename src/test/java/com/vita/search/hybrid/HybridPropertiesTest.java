package com.vita.search.hybrid;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class HybridPropertiesTest {

	private static HybridProperties bind(Map<String, String> values) {
		return new Binder(new MapConfigurationPropertySource(values))
				.bind("search.hybrid", HybridProperties.class)
				.orElseGet(() -> new HybridProperties(false, false, null, null));
	}

	@Test
	void isOffByDefaultAndUsesTheDomainSpecificDefaultWeights() {
		HybridProperties properties = bind(Map.of());

		assertThat(properties.enabled()).isFalse();
		assertThat(properties.createIndexes()).isFalse();
		// FAQ는 벡터 쪽, 요금제는 키워드 쪽에 가중
		assertThat(properties.faq().weights()).isEqualTo(new HybridWeights(2.0, 1.0, 60));
		assertThat(properties.plan().weights()).isEqualTo(new HybridWeights(1.0, 2.0, 60));
		assertThat(properties.faq().candidateLimit()).isEqualTo(100);
		assertThat(properties.plan().candidateLimit()).isEqualTo(50);
	}

	@Test
	void bindsConfiguredValuesAndKeepsDefaultsForTheOnesLeftOut() {
		HybridProperties properties = bind(Map.of(
				"search.hybrid.enabled", "true",
				"search.hybrid.create-indexes", "true",
				"search.hybrid.faq.vector-weight", "1.0",
				"search.hybrid.faq.keyword-weight", "0.0",
				"search.hybrid.plan.rrf-k", "30"));

		assertThat(properties.enabled()).isTrue();
		assertThat(properties.createIndexes()).isTrue();
		assertThat(properties.faq().weights()).isEqualTo(new HybridWeights(1.0, 0.0, 60));
		assertThat(properties.plan().weights()).isEqualTo(new HybridWeights(1.0, 2.0, 30));
	}

	@Test
	void candidateLimitIsNeverSmallerThanThePoolSize() {
		HybridProperties properties = bind(Map.of("search.hybrid.plan.candidate-limit", "10"));

		assertThat(properties.plan().candidateLimitFor(50)).isEqualTo(50);
		assertThat(properties.plan().candidateLimitFor(5)).isEqualTo(10);
	}
}
