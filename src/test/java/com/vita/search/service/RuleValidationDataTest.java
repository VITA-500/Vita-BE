package com.vita.search.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vita.search.service.IrrelevantQueryDetector.Rule;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * 규칙 검증 데이터({@code rule_validation_queries.jsonl})로 무관 질문 규칙의 오탐과 재현율이 지금보다 나빠지지 않는지 지킨다.
 * 서비스 평가(Baseline)에 쓰는 정상 질문 평가셋에는 "저는 ○○인데 …" 같은 자기소개형 질문이 거의 없어서, 오탐이 거기서는 안 보인다.
 * 그래서 이런 질문을 이 데이터에 NORMAL로 두고 막히지 않는지 확인한다.
 */
class RuleValidationDataTest {

	private record Row(String kind, String query) {
	}

	private static List<Row> load() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		List<Row> rows = new ArrayList<>();
		try (InputStream in = new ClassPathResource("data/regression/rule_validation_queries.jsonl").getInputStream()) {
			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
				if (!line.isBlank()) {
					JsonNode node = mapper.readTree(line);
					rows.add(new Row(node.get("kind").asText(), node.get("query").asText()));
				}
			}
		}
		return rows;
	}

	@Test
	void neverBlocksANormalQuestion() throws Exception {
		List<String> blocked = new ArrayList<>();
		for (Row row : load()) {
			Set<Rule> rules = IrrelevantQueryDetector.detect(row.query());
			if ("NORMAL".equals(row.kind()) && !rules.isEmpty()) {
				blocked.add(row.query() + " -> " + rules);
			}
		}

		assertThat(blocked).as("정상 질문이 규칙에 막혔다").isEmpty();
	}

	@Test
	void keepsBlockingPersonalQuestionsAtLeastAsOftenAsBefore() throws Exception {
		int personal = 0;
		int blocked = 0;
		for (Row row : load()) {
			if ("PERSONAL".equals(row.kind())) {
				personal++;
				if (IrrelevantQueryDetector.detect(row.query()).contains(Rule.PERSONAL_LOOKUP)) {
					blocked++;
				}
			}
		}

		// 40문항 중 35개를 막는다. 나머지 5개는 이전부터 알려진 미탐이다("제 요금 납부 내역 좀 조회할 수 있을까요?"는 FAQ와 모양이
		// 같아서 일부러 못 잡고, 나머지는 "저번 달에 로밍으로 나온 요금이 얼마였는지"처럼 1인칭 없이 쓰인 질문이다).
		assertThat(personal).isEqualTo(40);
		assertThat(blocked).isGreaterThanOrEqualTo(35);
	}

	@Test
	void blocksEveryCompetitorQuestion() throws Exception {
		int competitor = 0;
		int blocked = 0;
		for (Row row : load()) {
			if ("COMPETITOR".equals(row.kind())) {
				competitor++;
				Set<Rule> rules = IrrelevantQueryDetector.detect(row.query());
				if (rules.contains(Rule.COMPETITOR_BRAND) || rules.contains(Rule.COMPETITOR_GENERIC)) {
					blocked++;
				}
			}
		}

		assertThat(blocked).isEqualTo(competitor);
	}
}
