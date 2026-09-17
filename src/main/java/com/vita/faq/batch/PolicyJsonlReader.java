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
public class PolicyJsonlReader {

	private final ObjectMapper objectMapper;

	public PolicyJsonlReader(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public List<PolicyRecord> read(Resource resource) {
		List<PolicyRecord> policies = new ArrayList<>();
		Set<String> policyIds = new HashSet<>();
		try (BufferedReader reader = new BufferedReader(
			new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			int lineNumber = 0;
			while ((line = reader.readLine()) != null) {
				lineNumber++;
				if (line.isBlank()) {
					continue;
				}
				PolicyRecord policy = parse(line, lineNumber);
				validate(policy, lineNumber);
				if (!policyIds.add(policy.policyId())) {
					throw invalid(lineNumber, "중복된 policy_id입니다: " + policy.policyId());
				}
				policies.add(policy);
			}
		} catch (IOException exception) {
			throw new IllegalStateException("정책 JSONL을 읽을 수 없습니다: " + resource.getDescription(), exception);
		}
		return List.copyOf(policies);
	}

	private PolicyRecord parse(String line, int lineNumber) {
		try {
			return objectMapper.readValue(line, PolicyRecord.class);
		} catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("정책 JSONL " + lineNumber + "번째 줄의 JSON 형식이 잘못되었습니다.", exception);
		}
	}

	private void validate(PolicyRecord policy, int lineNumber) {
		requireText(policy.policyId(), "policy_id", lineNumber);
		requireText(policy.category(), "category", lineNumber);
		requireText(policy.subcategory(), "subcategory", lineNumber);
		requireText(policy.topic(), "topic", lineNumber);
		requireText(policy.sourceTitle(), "source_title", lineNumber);
		requireText(policy.sourceUrl(), "source_url", lineNumber);
		if (!FaqTaxonomy.supports(policy.category())) {
			throw invalid(lineNumber, "지원하지 않는 category입니다: " + policy.category());
		}
		if (policy.facts() == null || policy.facts().isEmpty()
			|| policy.facts().stream().anyMatch(fact -> fact == null || fact.isBlank())) {
			throw invalid(lineNumber, "facts에는 비어 있지 않은 정책 문장이 하나 이상 필요합니다.");
		}
		if (policy.checkedAt() == null) {
			throw invalid(lineNumber, "checked_at이 필요합니다.");
		}
	}

	private void requireText(String value, String field, int lineNumber) {
		if (value == null || value.isBlank()) {
			throw invalid(lineNumber, field + "이(가) 필요합니다.");
		}
	}

	private IllegalArgumentException invalid(int lineNumber, String message) {
		return new IllegalArgumentException("정책 JSONL " + lineNumber + "번째 줄: " + message);
	}
}
