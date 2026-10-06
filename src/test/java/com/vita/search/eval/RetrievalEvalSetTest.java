package com.vita.search.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 평가셋 파일(retrieval_eval_v2.jsonl)이 약속한 규칙을 지키는지 확인한다. DB 없이 파일만 읽는다. */
class RetrievalEvalSetTest {

	private static final int MAX_RELEVANT_PER_QUESTION = 10;

	private static List<RetrievalEvalQuestion> questions;

	@BeforeAll
	static void loadEvalSet() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		List<RetrievalEvalQuestion> loaded = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
				new ClassPathResource("data/regression/eval_v2/retrieval_eval_v2.jsonl").getInputStream(),
				StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (!line.isBlank()) {
					loaded.add(mapper.readValue(line, RetrievalEvalQuestion.class));
				}
			}
		}
		questions = loaded;
	}

	@Test
	void hasTwoHundredQuestionsWithUniqueIdsAndAFullSubsetOrder() {
		assertThat(questions).hasSize(200);
		assertThat(questions.stream().map(RetrievalEvalQuestion::qid).collect(Collectors.toSet())).hasSize(200);
		assertThat(questions.stream().map(RetrievalEvalQuestion::query).collect(Collectors.toSet())).hasSize(200);
		// 50/100/150/200개로 자를 수 있도록 순서가 1..200을 한 번씩 쓴다.
		assertThat(questions.stream().map(RetrievalEvalQuestion::subsetOrder).sorted().toList())
				.isEqualTo(IntStream.rangeClosed(1, 200).boxed().toList());
	}

	@Test
	void everyQuestionHasADirectAnswerAndAtMostTenRelevantFaqs() {
		for (RetrievalEvalQuestion question : questions) {
			Set<String> grade3 = question.relevantIds(3);
			assertThat(grade3).as(question.qid() + " 3점 정답").isNotEmpty();
			assertThat(grade3).as(question.qid() + " 시드 FAQ는 3점").containsAll(question.seedFaqIds());
			int relevant = question.relevantIds(1).size();
			assertThat(relevant).as(question.qid() + " 정답 FAQ 수").isBetween(4, MAX_RELEVANT_PER_QUESTION);
		}
	}

	@Test
	void aFaqAppearsOnlyOnceAcrossAllGradesOfAQuestion() {
		for (RetrievalEvalQuestion question : questions) {
			List<String> all = new ArrayList<>();
			for (String grade : List.of("3", "2", "1")) {
				question.relevance().getOrDefault(grade, List.of()).forEach(all::addAll);
			}
			assertThat(new HashSet<>(all)).as(question.qid() + " 중복 FAQ").hasSameSizeAs(all);
		}
	}

	@Test
	void groupGradesAreSortedFromHighestToLowest() {
		RetrievalEvalQuestion question = questions.get(0);
		List<Integer> grades = question.groupGradesDescending();
		assertThat(grades).isSortedAccordingTo(java.util.Comparator.reverseOrder());
		assertThat(grades.get(0)).isEqualTo(3);
		assertThat(question.gradeById().keySet()).isEqualTo(question.relevantIds(1));
	}
}
