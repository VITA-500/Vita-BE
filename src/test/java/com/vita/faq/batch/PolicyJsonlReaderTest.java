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

class PolicyJsonlReaderTest {

	private final PolicyJsonlReader reader = new PolicyJsonlReader(
		new ObjectMapper().findAndRegisterModules()
	);

	@Test
	void readsUtf8JsonLinesAndSkipsBlankLines() {
		String jsonl = """
			{"policy_id":"POL-ROAM-001","category":"로밍","subcategory":"로밍 신청","topic":"가입 확인","facts":["앱에서 확인할 수 있다."],"source_title":"[해외로밍] 어디에서 확인하나요?","source_url":"https://example.com","checked_at":"2026-09-16"}

			""";

		List<PolicyRecord> result = reader.read(resource(jsonl));

		assertThat(result).hasSize(1);
		assertThat(result.getFirst().policyId()).isEqualTo("POL-ROAM-001");
		assertThat(result.getFirst().facts()).containsExactly("앱에서 확인할 수 있다.");
	}

	@Test
	void acceptsNewSubcategoryForSupportedCategory() {
		String jsonl = """
			{"policy_id":"POL-ROAM-001","category":"로밍","subcategory":"기타","topic":"가입 확인","facts":["사실"],"source_title":"질문","source_url":"https://example.com","checked_at":"2026-09-16"}
			""";

		assertThat(reader.read(resource(jsonl))).hasSize(1);
	}

	@Test
	void rejectsUnsupportedCategoryWithLineNumber() {
		String jsonl = """
			{"policy_id":"POL-UNKNOWN-001","category":"기타","subcategory":"기타","topic":"가입 확인","facts":["사실"],"source_title":"질문","source_url":"https://example.com","checked_at":"2026-09-16"}
			""";

		assertThatThrownBy(() -> reader.read(resource(jsonl)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("1번째 줄")
			.hasMessageContaining("category");
	}

	@Test
	void rejectsMalformedJsonWithLineNumber() {
		assertThatThrownBy(() -> reader.read(resource("{not-json}\n")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("1번째 줄")
			.hasMessageContaining("JSON 형식");
	}

	@Test
	void bundledRoamingPoliciesContainTwoPoliciesPerSubcategory() {
		List<PolicyRecord> policies = reader.read(new ClassPathResource("data/faq/policy_cleaned.jsonl"));

		Map<String, Long> countBySubcategory = policies.stream()
			.collect(groupingBy(PolicyRecord::subcategory, counting()));

		assertThat(policies).hasSize(12);
		assertThat(policies).extracting(PolicyRecord::policyId).doesNotHaveDuplicates();
		assertThat(countBySubcategory).containsOnly(
			entry("로밍 신청", 2L),
			entry("로밍 요금", 2L),
			entry("데이터 로밍", 2L),
			entry("통화·문자 로밍", 2L),
			entry("로밍 해지", 2L),
			entry("국가별 이용", 2L)
		);
	}

	@Test
	void readsBundledBillingPolicies() {
		List<PolicyRecord> policies = reader.read(
			new ClassPathResource("data/faq/policy/policy_billing_test.jsonl")
		);

		assertThat(policies).hasSize(10);
		assertThat(policies).extracting(PolicyRecord::policyId).doesNotHaveDuplicates();
		assertThat(policies).extracting(PolicyRecord::category).containsOnly("요금/납부");
	}

	@Test
	void readsBundledUsimEsimPolicies() {
		List<PolicyRecord> policies = reader.read(
			new ClassPathResource("data/faq/policy/policy_usim_esim_test.jsonl")
		);

		assertThat(policies).hasSize(10);
		assertThat(policies).extracting(PolicyRecord::policyId).doesNotHaveDuplicates();
		assertThat(policies).extracting(PolicyRecord::category).containsOnly("유심/eSIM");
	}

	private ByteArrayResource resource(String content) {
		return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
	}
}
