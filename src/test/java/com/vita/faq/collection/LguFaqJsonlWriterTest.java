package com.vita.faq.collection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LguFaqJsonlWriterTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final LguFaqJsonlWriter writer = new LguFaqJsonlWriter(objectMapper);

	@TempDir
	Path tempDirectory;

	@Test
	void writesUtf8JsonLinesInDeterministicOrder() throws IOException {
		Path output = tempDirectory.resolve("nested/faq_all_cleaned.jsonl");
		LguFaqCollectedRecord roaming = faq("KNOW-2", "로밍", "서비스안내", "해외에서 로밍할 수 있나요?");
		LguFaqCollectedRecord billing = faq("KNOW-1", "요금/납부", "요금조회", "청구요금은 어디서 확인하나요?");

		writer.write(output, List.of(billing, roaming));

		List<String> lines = Files.readAllLines(output, StandardCharsets.UTF_8);
		assertThat(lines).hasSize(2).noneMatch(String::isBlank);

		JsonNode first = objectMapper.readTree(lines.getFirst());
		JsonNode second = objectMapper.readTree(lines.getLast());
		assertThat(first.get("faq_id").asText()).isEqualTo("KNOW-2");
		assertThat(first.get("category").asText()).isEqualTo("로밍");
		assertThat(first.get("source_policy_ids").get(0).asText()).isEqualTo("KNOW-2");
		assertThat(second.get("faq_id").asText()).isEqualTo("KNOW-1");
	}

	@Test
	void overwritesExistingOutput() throws IOException {
		Path output = tempDirectory.resolve("faq_all_cleaned.jsonl");
		writer.write(output, List.of(faq("KNOW-1", "요금/납부", "요금조회", "첫 질문")));

		writer.write(output, List.of(faq("KNOW-2", "로밍", "서비스안내", "두 번째 질문")));

		List<String> lines = Files.readAllLines(output, StandardCharsets.UTF_8);
		assertThat(lines).hasSize(1);
		assertThat(objectMapper.readTree(lines.getFirst()).get("faq_id").asText()).isEqualTo("KNOW-2");
	}

	@Test
	void readsExistingOutputForResume() {
		Path output = tempDirectory.resolve("faq_all_cleaned.jsonl");
		LguFaqCollectedRecord faq = faq("KNOW-1", "모바일", "요금제", "요금제 질문");
		writer.write(output, List.of(faq));

		assertThat(writer.readExisting(output))
			.extracting(LguFaqCollectedRecord::sourceFaqId)
			.containsExactly("KNOW-1");
	}

	@Test
	void rejectsEmptyFaqList() {
		Path output = tempDirectory.resolve("faq_all_cleaned.jsonl");

		assertThatThrownBy(() -> writer.write(output, List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("저장할 데이터가 없습니다");
	}

	private LguFaqCollectedRecord faq(String id, String category, String subcategory, String question) {
		return new LguFaqCollectedRecord(
			id,
			category,
			subcategory,
			question,
			"정제된 답변입니다.",
			"개인 > 공식 분류"
		);
	}
}
