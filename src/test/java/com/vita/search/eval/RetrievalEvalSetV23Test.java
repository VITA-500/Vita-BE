package com.vita.search.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * 정답지 v2.3(retrieval_eval_v2_3.jsonl)이 v2.2의 200문항을 그대로 두고, 새 FAQ를 반영한 정답 추가 6건과 신규 질문 24건만
 * 더한 판인지 확인한다. DB 없이 파일만 읽는다.
 */
class RetrievalEvalSetV23Test {

	private static final String V22 = "data/regression/eval_v2/retrieval_eval_v2_2.jsonl";
	private static final String V23 = "data/regression/eval_v2/retrieval_eval_v2_3.jsonl";
	private static final String FAQ_DATA = "data/faq/cleaned/faq_all_cleaned.jsonl";

	/** 새 FAQ가 질문에 실제로 답해 2점 정답이 추가된 기존 질문과 그 새 FAQ. */
	private static final Map<String, String> ADDED_GRADE2 = Map.of(
			"Q008", "APP-BENEFIT-17", "Q009", "APP-BENEFIT-19", "Q010", "APP-BENEFIT-16",
			"Q011", "APP-BENEFIT-21", "Q012", "APP-BENEFIT-15", "Q052", "VITA-SVC-02");

	private static final Set<String> STYLES = Set.of("FORMAL", "CASUAL", "SHORT", "SITUATION", "TYPO", "NEGATION");

	private static Map<String, JsonNode> v22;
	private static Map<String, JsonNode> v23;
	private static Set<String> faqIds;

	@BeforeAll
	static void load() throws Exception {
		v22 = read(V22);
		v23 = read(V23);
		faqIds = new java.util.HashSet<>();
		ObjectMapper mapper = new ObjectMapper();
		try (BufferedReader reader = reader(FAQ_DATA)) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (!line.isBlank()) {
					faqIds.add(mapper.readTree(line).get("faq_id").asText());
				}
			}
		}
	}

	private static BufferedReader reader(String resource) throws Exception {
		return new BufferedReader(new InputStreamReader(new ClassPathResource(resource).getInputStream(), StandardCharsets.UTF_8));
	}

	private static Map<String, JsonNode> read(String resource) throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		Map<String, JsonNode> rows = new LinkedHashMap<>();
		try (BufferedReader reader = reader(resource)) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (!line.isBlank()) {
					JsonNode node = mapper.readTree(line);
					rows.put(node.get("qid").asText(), node);
				}
			}
		}
		return rows;
	}

	/** 한 질문의 관련도별 정답 묶음을 "관련도:첫 FAQ ID" 목록으로 바꾼다. */
	private static List<String> groupsOf(JsonNode question) {
		List<String> groups = new ArrayList<>();
		question.get("relevance").fields().forEachRemaining(entry -> entry.getValue()
				.forEach(group -> groups.add(entry.getKey() + ":" + group.get(0).asText())));
		return groups;
	}

	@Test
	void keepsAllV22QuestionsUnchangedExceptTheSixAddedAnswers() {
		assertThat(v23).hasSize(v22.size() + 24);
		v22.forEach((qid, old) -> {
			JsonNode now = v23.get(qid);
			assertThat(now).as(qid).isNotNull();
			assertThat(now.get("query").asText()).as(qid + " 질문").isEqualTo(old.get("query").asText());
			assertThat(now.get("style").asText()).as(qid + " 말투").isEqualTo(old.get("style").asText());
			assertThat(now.get("subsetOrder").asInt()).as(qid + " 순서").isEqualTo(old.get("subsetOrder").asInt());
			List<String> oldGroups = groupsOf(old);
			List<String> nowGroups = groupsOf(now);
			if (ADDED_GRADE2.containsKey(qid)) {
				List<String> added = new ArrayList<>(nowGroups);
				added.removeAll(oldGroups);
				assertThat(added).as(qid + " 추가된 정답").containsExactly("2:" + ADDED_GRADE2.get(qid));
				assertThat(nowGroups).as(qid + " 기존 정답 유지").containsAll(oldGroups);
			} else {
				assertThat(nowGroups).as(qid + " 정답 변경 없음").isEqualTo(oldGroups);
			}
		});
	}

	@Test
	void addsTwentyFourNewQuestionsWithTheNewFaqAsTheOnlyTopAnswer() {
		List<String> newQids = v23.keySet().stream().filter(qid -> !v22.containsKey(qid)).toList();

		assertThat(newQids).isEqualTo(IntStream.rangeClosed(201, 224).mapToObj(i -> "Q" + i).toList());
		for (String qid : newQids) {
			JsonNode question = v23.get(qid);
			assertThat(STYLES).as(qid + " 말투").contains(question.get("style").asText());
			JsonNode three = question.get("relevance").get("3");
			assertThat(three).as(qid + " 3점 묶음").hasSize(1);
			// 3점 묶음은 새 FAQ 원문과 변형(-P1) 둘이고, 원문 ID가 출발점(seed)과 같다.
			assertThat(three.get(0)).as(qid).hasSize(2);
			String base = three.get(0).get(0).asText();
			assertThat(three.get(0).get(1).asText()).as(qid).isEqualTo(base + "-P1");
			assertThat(question.get("seedFaqIds").get(0).asText()).as(qid).isEqualTo(base);
			assertThat(faqIds).as(qid + " 정답 FAQ가 데이터에 있음").contains(base, base + "-P1");
		}
	}

	@Test
	void keepsQuestionIdsQueriesAndSubsetOrderUniqueAndContiguous() {
		assertThat(v23.values().stream().map(q -> q.get("query").asText()).collect(Collectors.toSet())).hasSize(v23.size());
		assertThat(v23.values().stream().map(q -> q.get("subsetOrder").asInt()).sorted().toList())
				.isEqualTo(IntStream.rangeClosed(1, v23.size()).boxed().toList());
	}

	@Test
	void everyAnswerFaqIdExistsInTheFaqData() {
		v23.forEach((qid, question) -> question.get("relevance").fields().forEachRemaining(entry -> entry.getValue()
				.forEach(group -> group.forEach(id -> assertThat(faqIds).as(qid + " " + id).contains(id.asText())))));
	}
}
