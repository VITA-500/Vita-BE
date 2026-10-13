package com.vita.search.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * 요금제 평가셋 v2(plan_eval_v2.jsonl)가 약속한 규칙을 지키는지 확인한다. DB 없이 파일만 읽는다.
 *
 * <p>v2는 요금제가 20종으로 늘어난 뒤(V20) 기준의 정답지다. v1의 질문 130개는 문구와 번호를 그대로 두고 정답만 다시 계산했고,
 * 신규 요금제를 겨냥한 질문을 뒤에 덧붙였다. v1 파일은 점수 재현을 위해 그대로 보존한다({@link PlanEvalSetTest}).
 */
class PlanEvalSetV2Test {

	private static final Pattern PLAN_CODE = Pattern.compile("'(VITA-[A-Z0-9-]+)'");

	/** v1 질문 수. v2의 앞쪽 질문 130개가 v1과 같아야 한다. */
	private static final int V1_QUESTION_COUNT = 130;

	/** 질문 유형 중 정답 요금제가 없어도 되는 유형. */
	private static final Set<String> NO_ANSWER_TYPES = Set.of("NONE", "NO_EXACT");

	/**
	 * v1에서 3점이던 정답이 v2에서 빠지거나 내려간, 의도한 변경. 키(질문 번호)와 요금제 코드 쌍이다.
	 * P068: 요금제가 연령대별로 나뉘면서 중학생은 틴 요금제 대상이 되어 키즈 5가 2점으로 내려갔다.
	 * P095: 최상급("데이터 제일 많은")은 가장 큰 하나만 정답이라 유스 100이 생기면서 유스 70이 빠졌다.
	 */
	private static final Set<String> INTENDED_DEMOTIONS = Set.of("P068:VITA-KIDS-5", "P095:VITA-YOUTH-70");

	private static List<PlanEvalQuestion> v1;
	private static List<PlanEvalQuestion> v2;
	private static Set<String> planCodes;

	@BeforeAll
	static void load() throws Exception {
		v1 = read("data/regression/eval_v2/plan_eval_v1.jsonl");
		v2 = read("data/regression/eval_v2/plan_eval_v2.jsonl");

		// 정답에 쓰인 요금제 코드가 실제 요금제(시드 15종 + V20 신규 5종)에 있는지 대조하려고 마이그레이션 SQL에서 코드를 읽는다.
		Set<String> codes = new TreeSet<>();
		for (String migration : List.of("db/migration/V9__seed_plans.sql", "db/migration/V20__expand_age_based_plans.sql")) {
			String sql = new String(new ClassPathResource(migration).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			Matcher matcher = PLAN_CODE.matcher(sql);
			while (matcher.find()) {
				codes.add(matcher.group(1));
			}
		}
		planCodes = codes;
	}

	private static List<PlanEvalQuestion> read(String path) throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		List<PlanEvalQuestion> loaded = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (!line.isBlank()) {
					loaded.add(mapper.readValue(line, PlanEvalQuestion.class));
				}
			}
		}
		return loaded;
	}

	@Test
	void keepsTheFirstQuestionsOfV1AndAppendsNewOnes() {
		assertThat(v2.size()).isGreaterThan(V1_QUESTION_COUNT);
		for (int i = 0; i < V1_QUESTION_COUNT; i++) {
			PlanEvalQuestion old = v1.get(i);
			PlanEvalQuestion now = v2.get(i);
			assertThat(now.qid()).isEqualTo(old.qid());
			assertThat(now.query()).as(old.qid() + " 질문 문구").isEqualTo(old.query());
			assertThat(now.type()).as(old.qid() + " 유형").isEqualTo(old.type());
			assertThat(now.subsetOrder()).as(old.qid() + " 순서").isEqualTo(old.subsetOrder());
		}
		assertThat(v2.stream().map(PlanEvalQuestion::qid).collect(Collectors.toSet())).hasSize(v2.size());
		assertThat(v2.stream().map(PlanEvalQuestion::query).collect(Collectors.toSet())).hasSize(v2.size());
		assertThat(v2.stream().map(PlanEvalQuestion::subsetOrder).sorted().toList())
				.isEqualTo(IntStream.rangeClosed(1, v2.size()).boxed().toList());
	}

	@Test
	void everyReferencedPlanExistsAndAppearsOnlyOnceInAQuestion() {
		assertThat(planCodes).hasSize(20);
		for (PlanEvalQuestion question : v2) {
			List<String> all = new ArrayList<>();
			for (String grade : question.relevance().keySet()) {
				assertThat(Set.of("3", "2", "1")).as(question.qid() + " 관련도 값").contains(grade);
				all.addAll(question.relevance().get(grade));
			}
			all.addAll(question.alternatives());
			assertThat(planCodes).as(question.qid() + " 요금제 코드").containsAll(all);
			assertThat(new HashSet<>(question.relevantCodes(1))).as(question.qid() + " 정답 중복")
					.hasSameSizeAs(question.relevance().values().stream().flatMap(List::stream).toList());
		}
	}

	@Test
	void answerShapeMatchesTheQuestionType() {
		for (PlanEvalQuestion question : v2) {
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
	void everyPlanIsNamedByOneNameQuestionAndSuperlativesHaveASortKey() {
		Set<String> sortKeys = Set.of("CHEAPEST", "MOST_EXPENSIVE", "MOST_DATA", "LEAST_DATA");
		Map<String, Integer> nameCount = new LinkedHashMap<>();
		for (PlanEvalQuestion question : v2) {
			if ("EXTREME".equals(question.type())) {
				assertThat(sortKeys).as(question.qid() + " 정렬 기준").contains(question.sortKey());
			} else {
				assertThat(question.sortKey()).as(question.qid()).isNull();
			}
			if ("NAME".equals(question.type())) {
				assertThat(question.relevantCodes(1)).as(question.qid() + " 이름 지목").hasSize(1);
				assertThat(question.relevantCodes(3)).as(question.qid()).hasSize(1);
				question.relevantCodes(3).forEach(code -> nameCount.merge(code, 1, Integer::sum));
			}
		}
		// 요금제 20종의 이름 지목 질문이 한 번씩 들어 있다.
		assertThat(nameCount.keySet()).isEqualTo(planCodes);
		assertThat(nameCount.values()).allMatch(count -> count == 1);
	}

	@Test
	void doesNotDropV1TopGradeAnswersExceptTheIntendedOnes() {
		Set<String> demoted = new HashSet<>();
		for (int i = 0; i < V1_QUESTION_COUNT; i++) {
			PlanEvalQuestion old = v1.get(i);
			Map<String, Integer> now = v2.get(i).gradeByCode();
			for (String code : old.relevantCodes(3)) {
				if (now.getOrDefault(code, 0) < 3) {
					demoted.add(old.qid() + ":" + code);
				}
			}
		}
		assertThat(demoted).isEqualTo(INTENDED_DEMOTIONS);
	}
}
