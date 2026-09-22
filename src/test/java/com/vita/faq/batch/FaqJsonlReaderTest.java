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
	void readsBundledSyntheticFaqsWithStableIdsAndSyntheticSources() {
		List<FaqJsonlRecord> faqs = reader.read(
			new ClassPathResource("data/faq/cleaned/faq_all_cleaned.jsonl")
		);
		assertThat(faqs).hasSize(1000);
		assertThat(faqs).extracting(FaqJsonlRecord::stableId).doesNotHaveDuplicates();
		assertThat(faqs).extracting(FaqJsonlRecord::question).doesNotHaveDuplicates();
		assertThat(faqs).allSatisfy(faq -> {
			assertThat(faq.faqId()).matches("SYN-\\d{6}");
			assertThat(faq.stableId()).isEqualTo(faq.faqId());
			assertThat(faq.sourcePolicyIds()).containsExactly("SYNTHETIC-GENERATED");
		});
	}

	@Test
	void bundledSyntheticFaqsCoverExactlyTheAllowedCategoryPairs() throws IOException {
		List<FaqJsonlRecord> faqs = reader.read(
			new ClassPathResource("data/faq/cleaned/faq_all_cleaned.jsonl")
		);
		Set<List<String>> allowedPairs = new HashSet<>();
		try (var input = new ClassPathResource("data/faq/category/faq_generation_categories.json").getInputStream()) {
			var taxonomy = new ObjectMapper().readTree(input);
			assertThat(taxonomy.path("categories").size()).isEqualTo(10);
			for (var category : taxonomy.path("categories")) {
				for (var subcategory : category.path("subcategories")) {
					allowedPairs.add(List.of(category.path("category").asText(), subcategory.asText()));
				}
			}
		}
		Set<List<String>> actualPairs = new HashSet<>();
		faqs.forEach(faq -> actualPairs.add(List.of(faq.category(), faq.subcategory())));
		assertThat(allowedPairs).hasSize(43);
		assertThat(actualPairs).containsExactlyInAnyOrderElementsOf(allowedPairs);
	}

	@Test
	void defaultImportResourcePointsToTheSharedDatasetWithoutEnablingAutomaticImport() {
		var yaml = new YamlPropertiesFactoryBean();
		yaml.setResources(new ClassPathResource("application.yml"));
		var properties = yaml.getObject();
		assertThat(properties).isNotNull();
		assertThat(properties.getProperty("faq.import.resource"))
			.isEqualTo("${FAQ_IMPORT_RESOURCE:classpath:data/faq/cleaned/faq_all_cleaned.jsonl}");
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
