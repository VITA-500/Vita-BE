package com.vita.faq.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.io.IOException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FaqJsonlReaderTest {

	private final FaqJsonlReader reader = new FaqJsonlReader(
		new ObjectMapper().findAndRegisterModules()
	);

	@Test
	void readsBundledFaqsWithStableIdsAndTraceableSources() {
		List<FaqJsonlRecord> faqs = reader.read(
			new ClassPathResource("data/faq/cleaned/faq_all_cleaned.jsonl")
		);
		assertThat(faqs).hasSize(1548);
		assertThat(faqs).extracting(FaqJsonlRecord::stableId).doesNotHaveDuplicates();
		assertThat(faqs).extracting(FaqJsonlRecord::question).doesNotHaveDuplicates();
		assertThat(faqs).allSatisfy(faq -> {
			assertThat(faq.faqId()).matches(
				"SYN-(?:\\d{6}|GAP-(?:MNP|LOSS|ESIM|LOGIN|ADDON|SPAM|MINOR)-\\d{3}|AUG-\\d{6}|EXP-B\\d{2}-\\d{3})"
			);
			assertThat(faq.stableId()).isEqualTo(faq.faqId());
			assertThat(faq.sourcePolicyIds()).singleElement()
				.satisfies(source -> assertThat(source).matches("(?:SYNTHETIC-.*|KNOW\\d{10})"));
		});
	}

	@Test
	void bundledSyntheticFaqsCoverExactlyTheLegacyCategoryPairs() throws IOException {
		List<FaqJsonlRecord> faqs = reader.read(
			new ClassPathResource("data/faq/cleaned/faq_all_cleaned.jsonl")
		);
		Set<List<String>> allowedPairs = new HashSet<>();
		try (var input = new ClassPathResource("data/faq/category/faq_generation_categories.json").getInputStream()) {
			var taxonomy = new ObjectMapper().readTree(input);
			assertThat(taxonomy.path("categories").size()).isEqualTo(11);
			for (var category : taxonomy.path("categories")) {
				for (var subcategory : category.path("subcategories")) {
					allowedPairs.add(List.of(category.path("category").asText(), subcategory.asText()));
				}
			}
		}
		Set<List<String>> actualPairs = new HashSet<>();
		faqs.forEach(faq -> actualPairs.add(List.of(faq.category(), faq.subcategory())));
		assertThat(allowedPairs).hasSize(47);
		Set<List<String>> legacyPairs = new HashSet<>(allowedPairs);
		legacyPairs.removeIf(pair -> pair.get(0).equals("VITA 이용 안내"));
		assertThat(legacyPairs).hasSize(43);
		assertThat(actualPairs).containsExactlyInAnyOrderElementsOf(legacyPairs);
	}

	@Test
	void defaultImportResourcePointsToTheSharedDatasetWithoutEnablingAutomaticImport() {
		var yaml = new YamlPropertiesFactoryBean();
		yaml.setResources(new ClassPathResource("application.yml"));
		var properties = yaml.getObject();
		assertThat(properties).isNotNull();
		assertThat(properties.getProperty("faq.import.resource"))
			.isEqualTo("${FAQ_IMPORT_RESOURCE:classpath:data/faq/cleaned/faq_all_cleaned.jsonl}");
		assertThat(properties.getProperty("embedding.base-url"))
            .isEqualTo("${EMBEDDING_BASE_URL:http://localhost:8081}");
        assertThat(properties.getProperty("faq.embedding.enabled"))
            .isEqualTo("${FAQ_EMBEDDING_ENABLED:false}");
        assertThat(properties.getProperty("plan.embedding.enabled"))
            .isEqualTo("${PLAN_EMBEDDING_ENABLED:false}");
        assertThat(properties.getProperty("faq.import.enabled"))
			.isEqualTo("${FAQ_IMPORT_ENABLED:false}");
	}

	@Test
	void rejectsEmptyFaqFile() {
		assertThatThrownBy(() -> reader.read(resource("")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("적재할 데이터가 없습니다");
	}

	@Test
	void rejectsFaqWithoutSourcePolicy() {
		String jsonl = """
			{"faq_id":"FAQ-001","category":"해외로밍","subcategory":"서비스안내","question":"질문","answer":"답변","source_policy_ids":[]}
			""";

		assertThatThrownBy(() -> reader.read(resource(jsonl)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("source_policy_ids");
	}

	@Test
	void acceptsSupportedNonRoamingCategory() {
		String jsonl = """
			{"faq_id":"FAQ-BILL-001","category":"요금 및 납부","subcategory":"요금조회","question":"이번 달 요금은 어디서 확인하나요?","answer":"앱에서 확인할 수 있습니다.","source_policy_ids":["POL-BILL-001"]}
			""";

		assertThat(reader.read(resource(jsonl))).hasSize(1);
	}

	@Test
	void acceptsOfficialLguCategory() {
		String jsonl = """
			{"faq_id":"KNOW-001","category":"해외로밍","subcategory":"서비스안내","question":"질문","answer":"답변","source_policy_ids":["KNOW-001"]}
			""";

		assertThat(reader.read(resource(jsonl))).hasSize(1);
	}

	@Test
	void acceptsAllVitaUsageGuidanceSubcategoriesWithTraceableSources() {
		for (String subcategory : List.of("계정·로그인", "챗봇 상담", "매장 찾기·예약", "제휴 혜택")) {
			String jsonl = """
				{"faq_id":"APP-TEST-001","category":"VITA 이용 안내","subcategory":"%s","question":"이용 방법을 알려주세요.","answer":"상담 화면에서 확인할 수 있습니다.","source_policy_ids":["APP:VITA"]}
				""".formatted(subcategory);
			assertThat(reader.read(resource(jsonl))).singleElement().satisfies(faq -> {
				assertThat(faq.stableId()).isEqualTo("APP-TEST-001");
				assertThat(faq.category()).isEqualTo("VITA 이용 안내");
				assertThat(faq.subcategory()).isEqualTo(subcategory);
				assertThat(faq.sourcePolicyIds()).containsExactly("APP:VITA");
			});
		}
	}

	@Test
	void rejectsVitaSubcategoryUnderWrongParentAndUnrelatedSubcategoryUnderVita() {
		for (List<String> pair : List.of(List.of("모바일", "계정·로그인"), List.of("VITA 이용 안내", "요금제"))) {
			String jsonl = """
				{"faq_id":"APP-TEST-001","category":"%s","subcategory":"%s","question":"질문","answer":"답변","source_policy_ids":["APP:VITA"]}
				""".formatted(pair.get(0), pair.get(1));
			assertThatThrownBy(() -> reader.read(resource(jsonl)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("category에 속하지 않는 subcategory");
		}
	}

	@Test
	void rejectsUnsupportedCategory() {
		String jsonl = """
			{"faq_id":"FAQ-UNKNOWN-001","category":"기타","subcategory":"기타","question":"질문","answer":"답변","source_policy_ids":["POL-UNKNOWN-001"]}
			""";

		assertThatThrownBy(() -> reader.read(resource(jsonl)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("지원하지 않는 category");
	}

    @Test
    void syntheticSourceDoesNotBypassCategoryValidation() {
        String jsonl = """
            {"faq_id":"SYN-000001","category":"로밍","subcategory":"서비스안내","question":"질문","answer":"답변","source_policy_ids":["SYNTHETIC-GENERATED"]}
            """;
        assertThatThrownBy(() -> reader.read(resource(jsonl)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("지원하지 않는 category");
    }

    @Test
    void rejectsWrongSubcategoryForBothSyntheticAndCollectedFaqs() {
        for (String source : List.of("SYNTHETIC-GENERATED", "KNOW-001")) {
            String jsonl = """
                {"faq_id":"FAQ-001","category":"모바일","subcategory":"IPTV 장애/고장","question":"질문","answer":"답변","source_policy_ids":["%s"]}
                """.formatted(source);
            assertThatThrownBy(() -> reader.read(resource(jsonl)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("category에 속하지 않는 subcategory");
        }
    }

	private ByteArrayResource resource(String content) {
		return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
	}
}
