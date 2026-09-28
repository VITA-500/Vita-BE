package com.vita.faq.batch;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

public record FaqJsonlRecord(
	@JsonProperty("faq_id") String faqId,
	String category,
	String subcategory,
	String question,
	String answer,
	@JsonProperty("source_policy_ids") List<String> sourcePolicyIds
) {
	public String stableId() {
		if (faqId != null && !faqId.isBlank()) {
			return faqId;
		}
		String source = category + "\u001f" + subcategory + "\u001f" + question;
		return "AUTO-" + UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
	}
}
