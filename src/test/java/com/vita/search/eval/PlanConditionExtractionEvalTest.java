package com.vita.search.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vita.search.service.PlanQueryConditionExtractor;
import com.vita.search.service.PlanQueryConditions;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

/**
 * 요금제 조건 추출기({@link PlanQueryConditionExtractor})가 질문에서 월 요금·데이터량·대상 그룹·무제한 여부를 얼마나 맞게
 * 읽는지 재는 검증 세트(plan_condition_eval_v1.jsonl) 테스트. DB 없이 파일과 추출기만 쓴다.
 *
 * <p>정답은 "질문한 사람이 의도한 조건"이라, 추출기가 아직 못 읽거나 잘못 읽는 질문도 세트에 들어 있다. 그래서 모든 문항을
 * 통과시키는 테스트가 아니라 (1) 세트 파일이 약속한 형식을 지키는지 확인하고 (2) 결과 요약과 틀린 문항을 로그로 남기고
 * (3) 추출기가 지금보다 나빠지지 않았는지만 확인한다. 추출기를 고쳐서 점수가 오르면 {@link #BASELINE_EXACT_CASES}를 올린다.
 *
 * <p>결과를 보려면 {@code ./gradlew test --tests PlanConditionExtractionEvalTest -i}로 실행한다.
 */
class PlanConditionExtractionEvalTest {

	private static final Logger log = LoggerFactory.getLogger(PlanConditionExtractionEvalTest.class);

	/** 검증 세트 한 문항. expect에 없는 항목은 "그 조건이 없어야 한다"는 뜻이다. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	record EvalCase(String id, String category, String query, Map<String, Object> expect, boolean fuzzy, String note) {
	}

	/** 조건 항목 8개. 추출기 결과에서 값을 꺼내는 방법을 함께 둔다. 통화·문자 정책은 plans.voice_policy, plans.sms_policy와 비교하는 조건이다. */
	private enum Field {
		FEE_MIN("feeMin", "금액"), FEE_MAX("feeMax", "금액"), DATA_MB_MIN("dataMbMin", "데이터량"),
		DATA_MB_MAX("dataMbMax", "데이터량"), TARGET_GROUP("targetGroup", "대상"), DATA_POLICY("dataPolicy", "무제한"),
		VOICE_POLICY("voicePolicy", "통화·문자"), SMS_POLICY("smsPolicy", "통화·문자");

		final String key;
		final String group;

		Field(String key, String group) {
			this.key = key;
			this.group = group;
		}

		/** 추출 결과에서 이 항목의 값. 숫자는 비교하기 쉽게 Long으로 맞춘다. */
		Object valueOf(PlanQueryConditions c) {
			return switch (this) {
				case FEE_MIN -> c.feeMin() == null ? null : c.feeMin().longValue();
				case FEE_MAX -> c.feeMax() == null ? null : c.feeMax().longValue();
				case DATA_MB_MIN -> c.dataMbMin();
				case DATA_MB_MAX -> c.dataMbMax();
				case TARGET_GROUP -> c.targetGroup();
				case DATA_POLICY -> c.dataPolicy();
				case VOICE_POLICY -> c.voicePolicy();
				case SMS_POLICY -> c.smsPolicy();
			};
		}
	}

	/** 항목 하나를 정답과 비교한 결과. */
	private enum Verdict { OK, FALSE_POSITIVE, MISSED, WRONG_VALUE }

	/**
	 * 지금 추출기의 점수(2026-10-08, 커밋 d016189 기준, 검수 반영 후 147문항). 모든 조건이 정답과 같은 문항 수가 이보다 줄어들면
	 * 추출기가 나빠진 것이다.
	 * 추출기를 개선해 점수가 오르면 이 값을 새 점수로 올려서 그 이상을 지키게 한다.
	 */
	private static final int BASELINE_EXACT_CASES = 123;

	private static final Set<String> GROUPS = Set.of("GENERAL", "YOUTH", "SENIOR", "KIDS", "WATCH", "TABLET");
	private static final Set<String> POLICIES = Set.of("LIMITED", "UNLIMITED");
	/** plans.voice_policy, plans.sms_policy 값. */
	private static final Set<String> VOICE_SMS_POLICIES = Set.of("NONE", "LIMITED", "UNLIMITED");

	private static List<EvalCase> cases;

	@BeforeAll
	static void loadEvalSet() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		List<EvalCase> loaded = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
				new ClassPathResource("data/regression/eval_v2/plan_condition_eval_v1.jsonl").getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (!line.isBlank()) {
					loaded.add(mapper.readValue(line, EvalCase.class));
				}
			}
		}
		cases = loaded;
	}

	@Test
	void evalSetFollowsItsOwnRules() {
		assertThat(cases).hasSizeGreaterThanOrEqualTo(100);
		assertThat(cases.stream().map(EvalCase::id).distinct().count()).isEqualTo(cases.size());
		assertThat(cases.stream().map(EvalCase::query).distinct().count()).isEqualTo(cases.size());

		Set<String> keys = Set.of("feeMin", "feeMax", "dataMbMin", "dataMbMax", "targetGroup", "dataPolicy", "voicePolicy", "smsPolicy");
		for (EvalCase c : cases) {
			assertThat(c.query()).as(c.id() + " 질문").isNotBlank();
			assertThat(keys).as(c.id() + " 정답 항목 이름").containsAll(c.expect().keySet());
			Object group = c.expect().get("targetGroup");
			if (group != null) {
				assertThat(GROUPS).as(c.id() + " 대상 그룹 값").contains((String) group);
			}
			Object policy = c.expect().get("dataPolicy");
			if (policy != null) {
				assertThat(POLICIES).as(c.id() + " 무제한 여부 값").contains((String) policy);
			}
			for (String key : List.of("voicePolicy", "smsPolicy")) {
				Object value = c.expect().get(key);
				if (value != null) {
					assertThat(VOICE_SMS_POLICIES).as(c.id() + " " + key + " 값").contains((String) value);
				}
			}
			Number feeMin = (Number) c.expect().get("feeMin");
			Number feeMax = (Number) c.expect().get("feeMax");
			if (feeMin != null && feeMax != null) {
				assertThat(feeMin.longValue()).as(c.id() + " 월 요금 하한<=상한").isLessThanOrEqualTo(feeMax.longValue());
			}
			Number dataMin = (Number) c.expect().get("dataMbMin");
			Number dataMax = (Number) c.expect().get("dataMbMax");
			if (dataMin != null && dataMax != null) {
				assertThat(dataMin.longValue()).as(c.id() + " 데이터 하한<=상한").isLessThanOrEqualTo(dataMax.longValue());
			}
		}
	}

	@Test
	void extractorIsNotWorseThanBaselineAndReportsItsScore() {
		Map<String, int[]> byCategory = new LinkedHashMap<>();
		Map<Verdict, Integer> fieldCounts = new LinkedHashMap<>();
		Map<String, Map<Verdict, Integer>> groupCounts = new LinkedHashMap<>();
		List<String> failures = new ArrayList<>();
		int exactCases = 0;

		for (EvalCase c : cases) {
			PlanQueryConditions got = PlanQueryConditionExtractor.extract(c.query());
			List<String> problems = new ArrayList<>();
			for (Field field : Field.values()) {
				Verdict verdict = judge(c.expect().get(field.key), field.valueOf(got));
				fieldCounts.merge(verdict, 1, Integer::sum);
				groupCounts.computeIfAbsent(field.group, g -> new LinkedHashMap<>()).merge(verdict, 1, Integer::sum);
				if (verdict != Verdict.OK) {
					problems.add("%s %s 정답 %s -> 추출 %s".formatted(field.key, label(verdict), c.expect().get(field.key), field.valueOf(got)));
				}
			}
			int[] counts = byCategory.computeIfAbsent(c.category(), k -> new int[2]);
			counts[0]++;
			if (problems.isEmpty()) {
				counts[1]++;
				exactCases++;
			} else {
				failures.add("%s [%s]%s %s | %s".formatted(c.id(), c.category(), c.fuzzy() ? "(애매)" : "", c.query(), String.join(", ", problems)));
			}
		}

		int tp = fieldCounts.getOrDefault(Verdict.OK, 0);
		log.info("요금제 조건 추출 검증: 문항 {}개 중 모든 조건이 정확한 문항 {}개 ({}%)", cases.size(), exactCases, percent(exactCases, cases.size()));
		byCategory.forEach((category, counts) -> log.info("  {}: {}/{} 정확", category, counts[1], counts[0]));
		groupCounts.forEach((group, counts) -> log.info("  [{}] 오탐 {}, 미탐 {}, 값 틀림 {}", group,
				counts.getOrDefault(Verdict.FALSE_POSITIVE, 0), counts.getOrDefault(Verdict.MISSED, 0), counts.getOrDefault(Verdict.WRONG_VALUE, 0)));
		log.info("  항목 단위: 오탐 {}, 미탐 {}, 값 틀림 {}", fieldCounts.getOrDefault(Verdict.FALSE_POSITIVE, 0),
				fieldCounts.getOrDefault(Verdict.MISSED, 0), fieldCounts.getOrDefault(Verdict.WRONG_VALUE, 0));
		failures.forEach(failure -> log.info("  틀린 문항 {}", failure));

		assertThat(tp).isPositive();
		assertThat(exactCases)
				.as("조건이 모두 정확한 문항 수가 기준(%d)보다 줄었다. 추출기를 고쳤다면 틀린 문항 로그를 확인하세요.", BASELINE_EXACT_CASES)
				.isGreaterThanOrEqualTo(BASELINE_EXACT_CASES);
	}

	/** 정답 값과 추출 값을 비교한다. 정답이 null이면 "없어야 하는 조건"이다. */
	private static Verdict judge(Object expected, Object actual) {
		Object e = normalize(expected);
		Object a = normalize(actual);
		if (e == null && a == null) {
			return Verdict.OK;
		}
		if (e == null) {
			return Verdict.FALSE_POSITIVE;
		}
		if (a == null) {
			return Verdict.MISSED;
		}
		return Objects.equals(e, a) ? Verdict.OK : Verdict.WRONG_VALUE;
	}

	/** JSON에서 읽은 숫자(Integer/Long)와 추출 결과(Long)를 같은 타입으로 맞춘다. */
	private static Object normalize(Object value) {
		return value instanceof Number number ? Long.valueOf(number.longValue()) : value;
	}

	private static String label(Verdict verdict) {
		return switch (verdict) {
			case FALSE_POSITIVE -> "오탐";
			case MISSED -> "미탐";
			case WRONG_VALUE -> "값 틀림";
			case OK -> "정확";
		};
	}

	private static String percent(int count, int total) {
		return "%.1f".formatted(100.0 * count / total);
	}
}
