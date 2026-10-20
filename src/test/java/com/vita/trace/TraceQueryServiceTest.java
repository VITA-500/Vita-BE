package com.vita.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vita.common.exception.BusinessException;
import com.vita.common.page.PageRequest;
import com.vita.trace.TraceQueryRepository.Filter;
import com.vita.trace.TraceQueryRepository.SpanRow;
import com.vita.trace.TraceQueryRepository.TraceRow;

/** 조회 서비스가 개인정보를 가리고 질문 벡터를 빼며, 기록된 값으로 자동 표시를 계산하는지 확인한다. */
class TraceQueryServiceTest {

	private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 19, 14, 0, 0);

	private final TraceQueryRepository repository = mock(TraceQueryRepository.class);
	private final TraceQueryService service = new TraceQueryService(repository, new ObjectMapper());

	private static SpanRow span(long id, String type, int startSec, int endSec, String historyJson) {
		return new SpanRow(id, type, T0.plusSeconds(startSec), T0.plusSeconds(endSec), historyJson);
	}

	private void stubTrace(SpanRow... spans) {
		when(repository.findTrace(7)).thenReturn(Optional.of(
				new TraceRow(7L, 20L, "abc1234", T0, "{\"faq_threshold\":0.83,\"note\":\"담당 010-9999-8888\"}")));
		when(repository.findSpans(7)).thenReturn(List.of(spans));
	}

	@Test
	void masksPersonalInfoInEveryStringAndKeepsOtherValues() {
		stubTrace(span(1, "transform", 0, 2, "{\"question\":\"내 번호는 010-1234-5678\",\"nested\":{\"list\":[\"110-123-456789\"]},\"n\":3}"));

		TraceViews.Detail detail = service.detail(7);

		assertThat(detail.spans().get(0).history().toString()).contains("010-****-5678").contains("***-***-**6789")
				.doesNotContain("1234-5678").doesNotContain("456789\"");
		assertThat(detail.spans().get(0).history().get("n").asInt()).isEqualTo(3);
		assertThat(detail.config().get("note").asText()).isEqualTo("담당 010-****-8888");
		assertThat(detail.config().get("faq_threshold").asDouble()).isEqualTo(0.83);
	}

	@Test
	void dropsTheQueryVectorButKeepsItsDimensionCount() {
		stubTrace(span(1, "faq_retrieve", 0, 1, "{\"query\":\"로밍\",\"query_embedding\":[0.1,0.2,0.3]}"));

		var history = service.detail(7).spans().get(0).history();

		assertThat(history.has("query_embedding")).isFalse();
		assertThat(history.get("query_embedding_dims").asInt()).isEqualTo(3);
		assertThat(history.get("query").asText()).isEqualTo("로밍");
	}

	@Test
	void exposesSkippedReasonErrorAndTookMsPerSpanInOrder() {
		stubTrace(span(1, "transform", 0, 2, "{}"),
				span(2, "faq_retrieve", 2, 2, "{\"skipped\":\"DB_ONLY\"}"),
				span(3, "generation", 2, 5, "{\"error\":{\"type\":\"IllegalStateException\",\"message\":\"끊김\"}}"));

		TraceViews.Detail detail = service.detail(7);

		assertThat(detail.spans()).extracting(TraceViews.SpanView::type).containsExactly("transform", "faq_retrieve", "generation");
		assertThat(detail.spans().get(0).tookMs()).isEqualTo(2000);
		assertThat(detail.spans().get(1).skipped()).isEqualTo("DB_ONLY");
		assertThat(detail.spans().get(1).error()).isNull();
		assertThat(detail.spans().get(2).error().get("type").asText()).isEqualTo("IllegalStateException");
		assertThat(detail.totalMs()).isEqualTo(5000);
	}

	@Test
	void computesTheAutomaticFlagsFromRecordedValuesOnly() {
		stubTrace(
				span(1, "transform", 0, 1, "{\"fallback_reason\":\"BOTH_NULL\"}"),
				span(2, "faq_retrieve", 1, 2, "{\"candidates\":[{\"rank\":1,\"passed_threshold\":false}],"
						+ "\"context_cleared_by_rule\":true,\"irrelevant_rules\":[\"PERSONAL_LOOKUP\"]}"),
				span(3, "plan_retrieve", 2, 2, "{\"skipped\":\"NON_MOBILE_GUARD\"}"),
				span(4, "generation", 2, 12, "{}"));

		List<String> codes = service.detail(7).flags().stream().map(TraceViews.Flag::code).toList();

		assertThat(codes).contains("TRANSFORM_FAILED", "SEARCH_FAILURE_SUSPECTED", "RULE_BLOCKED", "SKIPPED", "SLOW");
		assertThat(codes).doesNotContain("ERROR");
	}

	@Test
	void raisesNoFlagsForACleanFastTrace() {
		stubTrace(span(1, "transform", 0, 1, "{\"fallback_reason\":null}"),
				span(2, "faq_retrieve", 1, 2, "{\"candidates\":[{\"rank\":1,\"passed_threshold\":true}],\"context_cleared_by_rule\":false}"),
				span(3, "generation", 2, 3, "{}"));

		assertThat(service.detail(7).flags()).isEmpty();
	}

	@Test
	void doesNotCallATraceSlowWhenTheTotalIsShort() {
		stubTrace(span(1, "transform", 0, 1, "{}"), span(2, "generation", 1, 4, "{}"));

		assertThat(service.detail(7).flags()).isEmpty();
	}

	@Test
	void survivesABrokenHistoryJsonAndReturnsNotFoundForAMissingTrace() {
		stubTrace(span(1, "transform", 0, 1, "{깨진 json"));

		assertThat(service.detail(7).spans().get(0).history().get("parse_error").asBoolean()).isTrue();
		when(repository.findTrace(404)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.detail(404)).isInstanceOf(BusinessException.class);
	}

	@Test
	void listMasksQuestionsAndPassesTheWhitelistedSortToTheRepository() {
		var item = new TraceViews.Item(1L, 20L, T0, null, "내 계좌 110-123-456789", null, 1200L, false);
		when(repository.findPage(any(), any())).thenAnswer(invocation ->
				new PageImpl<>(List.of(item), invocation.<Pageable>getArgument(1), 1));

		var response = service.list(new Filter(null, null, null, null, null), PageRequest.of(null, null, null, "totalMs,asc"));

		assertThat(response.content().get(0).question()).isEqualTo("내 계좌 ***-***-**6789");
		assertThat(response.totalCount()).isEqualTo(1);
		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
		org.mockito.Mockito.verify(repository).findPage(any(), pageable.capture());
		assertThat(pageable.getValue().getSort().getOrderFor("totalMs").isAscending()).isTrue();
		assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
	}

	@Test
	void listRejectsAnUnknownSortFieldAndAnOversizedPage() {
		Filter none = new Filter(null, null, null, null, null);

		assertThatThrownBy(() -> service.list(none, PageRequest.of(null, null, null, "history,desc")))
				.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> service.list(none, PageRequest.of(0, 101, null, null))).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> service.list(none, PageRequest.of(-1, 20, null, null))).isInstanceOf(BusinessException.class);
	}

	@Test
	void escapesLikeWildcardsInTheKeyword() {
		assertThat(TraceQueryRepository.escapeLike("100%_완료!")).isEqualTo("100!%!_완료!!");
	}
}
