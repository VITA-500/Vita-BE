package com.vita.trace;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vita.common.exception.BusinessException;
import com.vita.common.exception.ErrorCode;
import com.vita.common.page.PageRequest;
import com.vita.common.page.PageResponse;
import com.vita.common.util.MaskingUtil;
import com.vita.trace.TraceQueryRepository.Filter;
import com.vita.trace.TraceQueryRepository.SpanRow;
import com.vita.trace.TraceQueryRepository.TraceRow;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 조회용 trace 목록·상세. 저장된 기록은 개인정보가 든 원문 그대로이므로, 응답으로 나가기 전에 여기서 가린다.
 * 질문 벡터(768개 숫자)는 화면에 쓸모가 없고 용량이 커서 응답에서 뺀다(차원 수만 남긴다).
 */
@Service
@RequiredArgsConstructor
public class TraceQueryService {

	/** 목록 정렬을 허용하는 필드(API 이름). */
	static final Set<String> SORT_FIELDS = Set.of("createdAt", "totalMs", "id");

	static final int MAX_PAGE_SIZE = 100;

	/** 한 단계가 이 비율 이상을 차지하고 전체가 {@link #SLOW_TOTAL_MS} 이상이면 "느림"으로 표시한다. */
	static final double SLOW_SPAN_RATIO = 0.6;
	static final long SLOW_TOTAL_MS = 5000;

	/** 단계 기록에서 질문 벡터가 담긴 키. 응답에서는 빼고 차원 수만 남긴다. */
	private static final String EMBEDDING_KEY = "query_embedding";

	private final TraceQueryRepository repository;
	private final ObjectMapper objectMapper;

	/** 조건에 맞는 trace 목록. 기본 정렬은 최근 순이다. */
	public PageResponse<TraceViews.Item> list(Filter filter, PageRequest pageRequest) {
		if (pageRequest.page() < 0 || pageRequest.size() < 1 || pageRequest.size() > MAX_PAGE_SIZE) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "page는 0 이상, size는 1~" + MAX_PAGE_SIZE + "이어야 합니다.");
		}
		Page<TraceViews.Item> page = repository.findPage(filter, pageRequest.toSpringPageRequest(SORT_FIELDS, "createdAt,desc"));
		return PageResponse.from(page, item -> new TraceViews.Item(item.id(), item.messageId(), item.createdAt(),
				item.appVersion(), MaskingUtil.text(item.question()), item.unresolvedReason(), item.totalMs(), item.hasError()));
	}

	/** trace 한 건과 단계 전체. 없으면 404. */
	public TraceViews.Detail detail(long id) {
		TraceRow trace = repository.findTrace(id)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "처리 기록을 찾을 수 없습니다."));
		List<SpanRow> rows = repository.findSpans(id);

		List<TraceViews.SpanView> spans = new ArrayList<>();
		List<JsonNode> rawHistories = new ArrayList<>();
		for (SpanRow row : rows) {
			JsonNode raw = parse(row.historyJson());
			rawHistories.add(raw);
			spans.add(toSpanView(row, raw));
		}
		long total = totalMs(rows);
		return new TraceViews.Detail(trace.id(), trace.messageId(), trace.createdAt(), trace.appVersion(),
				masked(parse(trace.configJson())), total, flags(rows, rawHistories, total), spans);
	}

	private TraceViews.SpanView toSpanView(SpanRow row, JsonNode raw) {
		long took = Duration.between(row.startedAt(), row.endedAt()).toMillis();
		JsonNode skipped = raw.get("skipped");
		JsonNode error = raw.get("error");
		return new TraceViews.SpanView(row.id(), row.type(), row.startedAt(), row.endedAt(), took,
				skipped != null && skipped.isTextual() ? skipped.asText() : null,
				error == null || error.isNull() ? null : masked(error), masked(withoutEmbedding(raw)));
	}

	/** 질문 벡터를 빼고 차원 수만 남긴 사본. */
	private JsonNode withoutEmbedding(JsonNode raw) {
		if (!raw.isObject() || !raw.has(EMBEDDING_KEY)) {
			return raw;
		}
		ObjectNode copy = ((ObjectNode) raw).deepCopy();
		JsonNode vector = copy.remove(EMBEDDING_KEY);
		copy.put(EMBEDDING_KEY + "_dims", vector != null && vector.isArray() ? vector.size() : 0);
		return copy;
	}

	/** 모든 문자열 값에서 개인정보(전화번호, 계좌번호 모양의 숫자, 주민번호, 이메일)를 가린 사본. */
	JsonNode masked(JsonNode node) {
		if (node == null) {
			return null;
		}
		if (node.isTextual()) {
			return objectMapper.getNodeFactory().textNode(MaskingUtil.text(node.asText()));
		}
		if (node.isObject()) {
			ObjectNode out = objectMapper.createObjectNode();
			Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> field = fields.next();
				out.set(field.getKey(), masked(field.getValue()));
			}
			return out;
		}
		if (node.isArray()) {
			ArrayNode out = objectMapper.createArrayNode();
			node.forEach(element -> out.add(masked(element)));
			return out;
		}
		return node;
	}

	private JsonNode parse(String json) {
		try {
			return json == null ? objectMapper.createObjectNode() : objectMapper.readTree(json);
		} catch (JsonProcessingException e) {
			// 저장된 JSON이 깨져 있어도 목록·상세 조회 전체가 실패하지 않게 한다.
			return objectMapper.createObjectNode().put("parse_error", true);
		}
	}

	private static long totalMs(List<SpanRow> rows) {
		if (rows.isEmpty()) {
			return 0;
		}
		var first = rows.stream().map(SpanRow::startedAt).min(java.util.Comparator.naturalOrder()).get();
		var last = rows.stream().map(SpanRow::endedAt).max(java.util.Comparator.naturalOrder()).get();
		return Duration.between(first, last).toMillis();
	}

	/**
	 * 기록된 값만으로 계산한 자동 표시. 값을 새로 지어내지 않고 이미 남은 필드만 본다.
	 * 개인정보 마스킹 전의 값을 보지만 결과에는 문자열 설명만 담는다.
	 */
	static List<TraceViews.Flag> flags(List<SpanRow> rows, List<JsonNode> histories, long totalMs) {
		List<TraceViews.Flag> flags = new ArrayList<>();
		long slowest = -1;
		Long slowestId = null;
		String slowestType = null;
		for (int i = 0; i < rows.size(); i++) {
			SpanRow row = rows.get(i);
			JsonNode history = histories.get(i);
			long took = Duration.between(row.startedAt(), row.endedAt()).toMillis();
			if (took > slowest) {
				slowest = took;
				slowestId = row.id();
				slowestType = row.type();
			}
			if (history.hasNonNull("error")) {
				flags.add(new TraceViews.Flag("ERROR", "단계에서 오류가 났습니다: " + row.type(), row.id()));
			}
			if (history.hasNonNull("skipped")) {
				flags.add(new TraceViews.Flag("SKIPPED", row.type() + " 단계를 건너뜀: " + history.get("skipped").asText(), row.id()));
			}
			if (history.path("context_cleared_by_rule").asBoolean(false)) {
				flags.add(new TraceViews.Flag("RULE_BLOCKED", "무관 질문 규칙으로 검색 결과가 비워짐: " + history.path("irrelevant_rules"), row.id()));
			}
			if ("transform".equals(row.type()) && history.hasNonNull("fallback_reason")) {
				flags.add(new TraceViews.Flag("TRANSFORM_FAILED",
						"질문 변환이 원문으로 대체됨: " + history.get("fallback_reason").asText(), row.id()));
			}
			if ("faq_retrieve".equals(row.type()) && firstCandidateFailedThreshold(history)) {
				flags.add(new TraceViews.Flag("SEARCH_FAILURE_SUSPECTED", "1위 후보가 기준 유사도에 못 미침", row.id()));
			}
		}
		if (totalMs >= SLOW_TOTAL_MS && slowest >= totalMs * SLOW_SPAN_RATIO) {
			flags.add(new TraceViews.Flag("SLOW", slowestType + " 단계가 전체의 " + Math.round(slowest * 100.0 / totalMs) + "%를 차지함", slowestId));
		}
		return flags;
	}

	private static boolean firstCandidateFailedThreshold(JsonNode history) {
		JsonNode candidates = history.path("candidates");
		return candidates.isArray() && candidates.size() > 0 && !candidates.get(0).path("passed_threshold").asBoolean(true);
	}
}
