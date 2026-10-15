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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 요금제 평가셋 파일(plan_eval_v1.jsonl)이 약속한 규칙을 지키는지 확인한다. DB 없이 파일만 읽는다. */
class PlanEvalSetTest {

	private static final Pattern PLAN_CODE = Pattern.compile("'(VITA-[A-Z0-9-]+)'");

	/** 질문 유형 중 정답 요금제가 없어도 되는 유형. */
	private static final Set<String> NO_ANSWER_TYPES = Set.of("NONE", "NO_EXACT");

	private static List<PlanEvalQuestion> questions;
	private static Set<String> seedPlanCodes;

	@BeforeAll
	static void loadEvalSet() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		List<PlanEvalQuestion> loaded = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
				new ClassPathResource("data/regression/eval_v2/plan_eval_v1.jsonl").getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (!line.isBlank()) {
					loaded.add(mapper.readValue(line, PlanEvalQuestion.class));
				}
			}
		}
		questions = loaded;

		// 정답에 쓰인 요금제 코드가 실제 시드(요금제 15종)에 있는지 대조하려고 마이그레이션 SQL에서 코드를 읽는다.
		String seedSql = new String(new ClassPathResource("db/migration/V9__seed_plans.sql").getInputStream().readAllBytes(),
				StandardCharsets.UTF_8);
		Matcher matcher = PLAN_CODE.matcher(seedSql);
		Set<String> codes = new TreeSet<>();
		while (matcher.find()) {
			codes.add(matcher.group(1));
		}
		seedPlanCodes = codes;
	}

	@Test
	void hasUniqueQuestionsAndAFullSubsetOrder() {
		int size = questions.size();
		assertThat(size).isGreaterThanOrEqualTo(100);
		assertThat(questions.stream().map(PlanEvalQuestion::qid).collect(Collectors.toSet())).hasSize(size);
		assertThat(questions.stream().map(PlanEvalQuestion::query).collect(Collectors.toSet())).hasSize(size);
		assertThat(questions.stream().map(PlanEvalQuestion::subsetOrder).sorted().toList())
				.isEqualTo(IntStream.rangeClosed(1, size).boxed().toList());
	}

	@Test
	void seedHasFifteenPlans() {
		assertThat(seedPlanCodes).hasSize(15);
	}

	@Test
	void everyReferencedPlanExistsAndAppearsOnlyOnceInAQuestion() {
		for (PlanEvalQuestion question : questions) {
			List<String> all = new ArrayList<>();
			for (String grade : question.relevance().keySet()) {
				assertThat(Set.of("3", "2", "1")).as(question.qid() + " 관련도 값").contains(grade);
				all.addAll(question.relevance().get(grade));
			}
			all.addAll(question.alternatives());
			assertThat(seedPlanCodes).as(question.qid() + " 요금제 코드").containsAll(all);
			assertThat(new HashSet<>(question.relevantCodes(1))).as(question.qid() + " 정답 중복")
					.hasSameSizeAs(question.relevance().values().stream().flatMap(List::stream).toList());
		}
	}

	@Test
	void answerShapeMatchesTheQuestionType() {
		for (PlanEvalQuestion question : questions) {
			boolean noAnswerType = NO_ANSWER_TYPES.contains(question.type());
			if (question.expectNone()) {
				assertThat(question.type()).as(question.qid()).isEqualTo("NONE");
				assertThat(question.relevantCodes(1)).as(question.qid() + " 정답 없음").isEmpty();
				assertThat(question.alternatives()).as(question.qid()).isEmpty();
			} else if (question.noExactMatch()) {
				assertThat(question.type()).as(question.qid()).isEqualTo("NO_EXACT");
				assertThat(question.relevantCodes(1)).as(question.qid() + " 정답 없음").isEmpty();
				assertThat(question.alternatives()).as(question.qid() + " 대안").isNotEmpty();
			} else {
				assertThat(noAnswerType).as(question.qid() + " 유형과 플래그").isFalse();
				assertThat(question.relevantCodes(1)).as(question.qid() + " 정답").isNotEmpty();
			}
		}
	}

	@Test
	void superlativeQuestionsHaveAKnownSortKeyAndNamedQuestionsHaveExactlyOneAnswer() {
		Set<String> sortKeys = Set.of("CHEAPEST", "MOST_EXPENSIVE", "MOST_DATA", "LEAST_DATA");
		for (PlanEvalQuestion question : questions) {
			if ("EXTREME".equals(question.type())) {
				assertThat(sortKeys).as(question.qid() + " 정렬 기준").contains(question.sortKey());
			} else {
				assertThat(question.sortKey()).as(question.qid()).isNull();
			}
			if ("NAME".equals(question.type())) {
				assertThat(question.relevantCodes(1)).as(question.qid() + " 이름 지목").hasSize(1);
				assertThat(question.relevantCodes(3)).as(question.qid()).hasSize(1);
			}
		}
		// 15종 요금제 이름 지목 질문이 한 번씩 들어 있다.
		Set<String> named = questions.stream().filter(q -> "NAME".equals(q.type()))
				.flatMap(q -> q.relevantCodes(3).stream()).collect(Collectors.toSet());
		assertThat(named).isEqualTo(seedPlanCodes);
	}

	@Test
	void gradeByCodeKeepsTheHighestGrade() {
		PlanEvalQuestion question = questions.stream().filter(q -> q.relevance().containsKey("2")).findFirst().orElseThrow();
		assertThat(question.gradeByCode().keySet()).isEqualTo(question.relevantCodes(1));
		assertThat(question.relevantCodes(3)).isSubsetOf(question.relevantCodes(2));
	}
}
