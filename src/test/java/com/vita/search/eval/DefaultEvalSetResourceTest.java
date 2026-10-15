package com.vita.search.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * 평가 러너가 기본으로 읽는 평가셋(application.yml의 {@code search.eval.resource}, {@code search.plan-eval.resource})이
 * 새 Baseline 기준 파일을 가리키고, 그 파일이 실제로 있는지 확인한다. DB 없이 설정 파일만 읽는다.
 * 러너는 켜기 전에는 평가셋을 읽지 않으므로, 파일 이름을 잘못 적어도 이 테스트가 없으면 수동 실행 때에야 알게 된다.
 */
class DefaultEvalSetResourceTest {

	/** {@code ${환경변수:기본값}} 형태에서 기본값(classpath: 경로)만 뽑는다. */
	private static final Pattern DEFAULT_VALUE = Pattern.compile("\\$\\{[A-Z0-9_]+:(classpath:[^}]+)}");

	/** FAQ 평가의 기본 평가셋. 새 FAQ 32건을 반영한 정답지 v2.3(검수 완료)이 1,084건 기준 Baseline이다. */
	private static final String FAQ_DEFAULT = "classpath:data/regression/eval_v2/retrieval_eval_v2_3.jsonl";

	/** 요금제 평가의 기본 평가셋. 요금제 20종 기준으로 정답을 다시 계산한 v2가 새 Baseline 기준이다. */
	private static final String PLAN_DEFAULT = "classpath:data/regression/eval_v2/plan_eval_v2.jsonl";

	private static String defaultOf(String propertyName) {
		YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
		yaml.setResources(new ClassPathResource("application.yml"));
		Properties properties = yaml.getObject();
		assertThat(properties).isNotNull();
		String raw = properties.getProperty(propertyName);
		assertThat(raw).as(propertyName).isNotNull();
		Matcher matcher = DEFAULT_VALUE.matcher(raw);
		assertThat(matcher.matches()).as("%s 값 형식: %s", propertyName, raw).isTrue();
		return matcher.group(1);
	}

	@Test
	void faqEvalUsesTheReviewedV23AnswerKeyByDefault() {
		String location = defaultOf("search.eval.resource");

		assertThat(location).isEqualTo(FAQ_DEFAULT);
		assertThat(new ClassPathResource(location.substring("classpath:".length())).exists()).isTrue();
	}

	@Test
	void planEvalUsesTheTwentyPlanV2SetByDefault() {
		String location = defaultOf("search.plan-eval.resource");

		assertThat(location).isEqualTo(PLAN_DEFAULT);
		assertThat(new ClassPathResource(location.substring("classpath:".length())).exists()).isTrue();
	}
}
