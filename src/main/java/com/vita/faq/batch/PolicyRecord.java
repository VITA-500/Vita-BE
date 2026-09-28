package com.vita.faq.batch;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.List;

public record PolicyRecord(
	@JsonProperty("policy_id") String policyId,
	String category,
	String subcategory,
	String topic,
	List<String> facts,
	@JsonProperty("source_title") String sourceTitle,
	@JsonProperty("source_url") String sourceUrl,
	@JsonProperty("checked_at") LocalDate checkedAt
) {
}
