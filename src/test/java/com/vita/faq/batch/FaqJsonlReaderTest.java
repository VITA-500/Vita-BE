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
	void readsBundledBillingFaqs() {
		List<FaqJsonlRecord> faqs = reader.read(
			new ClassPathResource("data/faq/cleaned/faq_billing_test.jsonl")
		);

		assertThat(faqs).hasSize(22);
		assertThat(faqs).extracting(FaqJsonlRecord::stableId).doesNotHaveDuplicates();
		assertThat(faqs).extracting(FaqJsonlRecord::question).doesNotHaveDuplicates();
		assertThat(faqs).extracting(FaqJsonlRecord::category).containsOnly("요금/납부");
	}

	@Test
	void readsBundledUsimEsimFaqs() {
		List<FaqJsonlRecord> faqs = reader.read(
			new ClassPathResource("data/faq/cleaned/faq_usim_esim_test.jsonl")
		);

		assertThat(faqs).hasSize(19);
		assertThat(faqs).extracting(FaqJsonlRecord::stableId).doesNotHaveDuplicates();
		assertThat(faqs).extracting(FaqJsonlRecord::question).doesNotHaveDuplicates();
		assertThat(faqs).extracting(FaqJsonlRecord::category).containsOnly("유심/eSIM");
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

	@Test
	void acceptsSupportedNonRoamingCategory() {
		String jsonl = """
			{"faq_id":"FAQ-BILL-001","category":"요금/납부","subcategory":"요금조회","question":"이번 달 요금은 어디서 확인하나요?","answer":"앱에서 확인할 수 있습니다.","source_policy_ids":["POL-BILL-001"]}
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

	private ByteArrayResource resource(String content) {
		return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
	}
}
