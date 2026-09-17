package com.vita.faq.batch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class FaqJsonlReader {

	private final ObjectMapper objectMapper;

	public FaqJsonlReader(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public List<FaqJsonlRecord> read(Resource resource) {
		List<FaqJsonlRecord> faqs = new ArrayList<>();
		Set<String> faqIds = new HashSet<>();
		try (BufferedReader reader = new BufferedReader(
			new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			int lineNumber = 0;
			while ((line = reader.readLine()) != null) {
				lineNumber++;
				if (line.isBlank()) {
					continue;
				}
				FaqJsonlRecord faq = parse(line, lineNumber);
				validate(faq, lineNumber);
				if (!faqIds.add(faq.stableId())) {
					throw invalid(lineNumber, "중복된 FAQ 질문입니다: " + faq.question());
				}
				faqs.add(faq);
			}
		} catch (IOException exception) {
			throw new IllegalStateException("FAQ JSONL을 읽을 수 없습니다: " + resource.getDescription(), exception);
		}
		if (faqs.isEmpty()) {
			throw new IllegalArgumentException("FAQ JSONL에 적재할 데이터가 없습니다: " + resource.getDescription());
		}
		return List.copyOf(faqs);
	}

	private FaqJsonlRecord parse(String line, int lineNumber) {
		try {
			return objectMapper.readValue(line, FaqJsonlRecord.class);
		} catch (JsonProcessingException exception) {
			throw invalid(lineNumber, "JSON 형식이 잘못되었습니다.", exception);
		}
	}

	private void validate(FaqJsonlRecord faq, int lineNumber) {
		requireText(faq.category(), "category", lineNumber);
		requireText(faq.subcategory(), "subcategory", lineNumber);
		requireText(faq.question(), "question", lineNumber);
		requireText(faq.answer(), "answer", lineNumber);
		if (!FaqTaxonomy.supports(faq.category())) {
			throw invalid(lineNumber, "지원하지 않는 category입니다: " + faq.category());
		}
		if (faq.sourcePolicyIds() == null || faq.sourcePolicyIds().isEmpty()
			|| faq.sourcePolicyIds().stream().anyMatch(id -> id == null || id.isBlank())) {
			throw invalid(lineNumber, "source_policy_ids에는 정책 ID가 하나 이상 필요합니다.");
		}
	}

	private void requireText(String value, String field, int lineNumber) {
		if (value == null || value.isBlank()) {
			throw invalid(lineNumber, field + "이(가) 필요합니다.");
		}
	}

	private IllegalArgumentException invalid(int lineNumber, String message) {
		return new IllegalArgumentException("FAQ JSONL " + lineNumber + "번째 줄: " + message);
	}

	private IllegalArgumentException invalid(int lineNumber, String message, Exception cause) {
		return new IllegalArgumentException("FAQ JSONL " + lineNumber + "번째 줄: " + message, cause);
	}
}
