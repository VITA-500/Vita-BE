package com.vita.search.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * 정답지 v2.2(retrieval_eval_v2_2.jsonl)가 v2.1과 질문은 같고 정답 라벨만 정리된 판인지, 약속한 규칙을 지키는지 확인한다.
 * DB 없이 파일만 읽는다. v2.1의 규칙은 {@link RetrievalEvalSetTest}가 지킨다.
 */
class RetrievalEvalSetV22Test {

	private static final String V21 = "data/regression/eval_v2/retrieval_eval_v2.jsonl";
	private static final String V22 = "data/regression/eval_v2/retrieval_eval_v2_2.jsonl";

	private static final int MAX_RELEVANT_PER_QUESTION = 10;

	/** 질문에 답하는 FAQ가 하나뿐이면 무관한 보조 정답을 두지 않고 3점 한 묶음(원문+변형)만 남기므로 v2.1의 4개보다 낮춘다. */
	private static final int MIN_RELEVANT_PER_QUESTION = 2;

	private static Map<String, RetrievalEvalQuestion> v21;
	private static Map<String, RetrievalEvalQuestion> v22;

	@BeforeAll
	static void loadEvalSets() throws Exception {
		v21 = load(V21);
		v22 = load(V22);
	}

	private static Map<String, RetrievalEvalQuestion> load(String resource) throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		Map<String, RetrievalEvalQuestion> loaded = new HashMap<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
				new ClassPathResource(resource).getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (!line.isBlank()) {
					RetrievalEvalQuestion question = mapper.readValue(line, RetrievalEvalQuestion.class);
					loaded.put(question.qid(), question);
				}
			}
		}
		return loaded;
	}

	@Test
	void keepsTheSameTwoHundredQuestionsAsV21AndOnlyChangesTheRelevanceLabels() {
		assertThat(v22).hasSize(200);
		assertThat(v22.keySet()).isEqualTo(v21.keySet());
		for (RetrievalEvalQuestion question : v22.values()) {
			RetrievalEvalQuestion old = v21.get(question.qid());
			assertThat(question.query()).as(question.qid() + " 질문").isEqualTo(old.query());
			assertThat(question.style()).as(question.qid() + " 말투").isEqualTo(old.style());
			assertThat(question.seedCategory()).as(question.qid() + " 시드 분류").isEqualTo(old.seedCategory());
			assertThat(question.seedSubcategory()).as(question.qid() + " 시드 세부분류").isEqualTo(old.seedSubcategory());
			assertThat(question.seedFaqIds()).as(question.qid() + " 시드 FAQ").isEqualTo(old.seedFaqIds());
			assertThat(question.subsetOrder()).as(question.qid() + " 서브셋 순서").isEqualTo(old.subsetOrder());
		}
	}

	@Test
	void everyQuestionKeepsItsSeedAsDirectAnswerAndRespectsTheSizeLimits() {
		for (RetrievalEvalQuestion question : v22.values()) {
			Set<String> grade3 = question.relevantIds(3);
			assertThat(grade3).as(question.qid() + " 3점 정답").containsAll(question.seedFaqIds());
			assertThat(question.relevantIds(1).size()).as(question.qid() + " 정답 FAQ 수")
					.isBetween(MIN_RELEVANT_PER_QUESTION, MAX_RELEVANT_PER_QUESTION);
		}
	}

	@Test
	void aFaqAppearsOnlyOnceAcrossAllGradesOfAQuestion() {
		for (RetrievalEvalQuestion question : v22.values()) {
			List<String> all = new ArrayList<>();
			for (String grade : List.of("3", "2", "1")) {
				question.relevance().getOrDefault(grade, List.of()).forEach(all::addAll);
			}
			assertThat(new HashSet<>(all)).as(question.qid() + " 중복 FAQ").hasSameSizeAs(all);
		}
	}

	/** 한 묶음은 원문과 변형(-P1) 쌍이다. 답변 텍스트가 같은 두 FAQ(번호변경안내 무료/유료)는 한 묶음이라 쌍이 둘일 수 있다. */
	@Test
	void everyBundleHoldsOriginalFaqsWithTheirVariantsOnly() {
		for (RetrievalEvalQuestion question : v22.values()) {
			for (List<String> group : question.relevantGroups(1)) {
				Set<String> bases = group.stream().map(id -> id.endsWith("-P1") ? id.substring(0, id.length() - 3) : id)
						.collect(Collectors.toSet());
				assertThat(bases).as(question.qid() + " 묶음 " + group + "의 원문 ID 수").hasSizeBetween(1, 2);
				assertThat(group).as(question.qid() + " 묶음 크기").hasSizeLessThanOrEqualTo(bases.size() * 2);
			}
		}
	}

	@Test
	void neverDemotesADirectAnswerOfV21() {
		for (RetrievalEvalQuestion question : v22.values()) {
			Set<String> oldGrade3 = v21.get(question.qid()).relevantIds(3);
			assertThat(question.relevantIds(3)).as(question.qid() + " v2.1의 3점은 v2.2에서도 3점").containsAll(oldGrade3);
		}
	}
}
