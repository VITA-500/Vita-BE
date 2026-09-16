package com.vita.faq.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

class FaqJsonlReaderTest {

	private final FaqJsonlReader reader = new FaqJsonlReader(
		new ObjectMapper().findAndRegisterModules()
	);

	@Test
	void readsBundledCleanedFaqs() {
		List<FaqJsonlRecord> faqs = reader.read(
			new ClassPathResource("data/faq/cleaned/faq_roaming_test.jsonl")
		);

		Map<String, Long> countBySubcategory = faqs.stream()
			.collect(groupingBy(FaqJsonlRecord::subcategory, counting()));

		assertThat(faqs).hasSize(23);
		assertThat(faqs).extracting(FaqJsonlRecord::stableId).doesNotHaveDuplicates();
		assertThat(faqs).extracting(FaqJsonlRecord::question).doesNotHaveDuplicates();
		assertThat(countBySubcategory).containsOnly(
			entry("로밍 신청", 4L),
			entry("로밍 요금", 5L),
			entry("데이터 로밍", 4L),
			entry("통화·문자 로밍", 5L),
			entry("로밍 해지", 2L),
			entry("국가별 이용", 3L)
		);
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
			{"faq_id":"FAQ-001","category":"로밍","subcategory":"로밍 신청","question":"질문","answer":"답변","source_policy_ids":[]}
			""";

		assertThatThrownBy(() -> reader.read(resource(jsonl)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("source_policy_ids");
	}

	private ByteArrayResource resource(String content) {
		return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
	}
}
