package com.vita.search.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vita.search.pipeline.QueryTransformer;
import com.vita.search.pipeline.TransformInfo;
import com.vita.search.pipeline.TransformedQuery;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 질문 변환 결과를 파일에 저장해 두고 다시 쓰는 평가용 변환기. LLM 변환(Query Transformation)은 같은 질문도 실행마다
 * 결과가 달라질 수 있어서, 변환 결과를 저장해 두지 않으면 "변환을 바꾼 실험(C)"과 "그 위에 검색 방식만 바꾼 실험(C+H)"을
 * 같은 변환 결과로 비교할 수 없다(변수가 하나 더 바뀐다). 저장된 변환 결과를 다시 쓰면 변환 호출(시간·비용)도 아낀다.
 *
 * <p>동작: 질문 원문을 키로 저장된 결과가 있으면 그대로 돌려주고(변환 호출 없음), 없으면 실제 변환기를 불러 결과를 저장한다.
 * 파일은 한 줄에 질문 하나인 JSONL이고, {@link #save()}가 호출될 때 쓴다. 파일 하나는 하나의 실험(변환기·프롬프트 버전) 전용으로
 * 쓰고, 프롬프트나 모델을 바꾸면 다른 파일을 쓴다(섞이면 어떤 변환으로 잰 점수인지 알 수 없다).
 *
 * <p>모델 호출이 예외로 실패한 결과({@link TransformInfo#REASON_CALL_FAILED})는 일시적인 실패일 수 있어 저장하지 않는다
 * (다음 실행에서 다시 시도한다). 응답이 비었거나 파싱에 실패한 결과처럼 모델 출력에서 나온 폴백은 저장한다.
 *
 * <p>스레드 안전하지 않은 평가 러너의 순차 호출을 가정하지만, 안전하게 쓰도록 메소드를 동기화했다.
 */
public class CachedQueryTransformer implements QueryTransformer {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** 파일 한 줄의 형식. 변환 결과와 변환 기록(어떻게 나온 결과인지)을 함께 저장한다. */
	record Entry(String original, String faqQuery, String planQuery,
			String kind, String reason, boolean faqNull, boolean planNull) {

		static Entry of(TransformedQuery transformed) {
			TransformInfo info = transformed.info();
			return new Entry(transformed.original(), transformed.faqQuery(), transformed.planQuery(),
					info.kind().name(), info.reason(), info.faqNull(), info.planNull());
		}

		TransformedQuery toTransformedQuery() {
			TransformInfo info = new TransformInfo(TransformInfo.Kind.valueOf(kind), reason, faqNull, planNull);
			return new TransformedQuery(original, faqQuery, planQuery, info);
		}
	}

	private final QueryTransformer delegate;
	private final Path file;
	private final Map<String, TransformedQuery> cache = new LinkedHashMap<>();
	private int hits;
	private int misses;
	private int skippedTransient;

	/**
	 * @param delegate 캐시에 없는 질문을 변환할 실제 변환기
	 * @param file     변환 결과를 저장하는 JSONL 파일. 이미 있으면 읽어서 쓴다
	 */
	public CachedQueryTransformer(QueryTransformer delegate, Path file) {
		this.delegate = delegate;
		this.file = file;
		load();
	}

	@Override
	public synchronized TransformedQuery transform(String query) {
		TransformedQuery cached = cache.get(query);
		if (cached != null) {
			hits++;
			return cached;
		}
		misses++;
		TransformedQuery fresh = delegate.transform(query);
		if (isTransientFailure(fresh)) {
			skippedTransient++;
		} else {
			cache.put(query, fresh);
		}
		return fresh;
	}

	/** 파일에서 읽은 변환 결과 수 + 이번 실행에서 새로 저장한 수. */
	public synchronized int size() {
		return cache.size();
	}

	/** 저장된 결과를 그대로 쓴 질문 수. */
	public synchronized int hits() {
		return hits;
	}

	/** 저장된 결과가 없어 실제 변환기를 부른 질문 수. */
	public synchronized int misses() {
		return misses;
	}

	/** 일시적 실패라 저장하지 않은 결과 수. */
	public synchronized int skippedTransient() {
		return skippedTransient;
	}

	/** 새로 변환한 결과가 있을 때만 파일을 다시 쓴다(저장된 결과만 쓴 실행은 파일을 건드리지 않는다). */
	public synchronized void save() {
		if (misses == 0 && Files.exists(file)) {
			return;
		}
		try {
			Path parent = file.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				for (TransformedQuery transformed : cache.values()) {
					writer.write(MAPPER.writeValueAsString(Entry.of(transformed)));
					writer.newLine();
				}
			}
		} catch (IOException exception) {
			throw new UncheckedIOException("질문 변환 결과를 저장하지 못했습니다: " + file, exception);
		}
	}

	private static boolean isTransientFailure(TransformedQuery transformed) {
		TransformInfo info = transformed.info();
		return info.kind() == TransformInfo.Kind.FALLBACK_ORIGINAL
				&& TransformInfo.REASON_CALL_FAILED.equals(info.reason());
	}

	private void load() {
		if (!Files.exists(file)) {
			return;
		}
		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String line;
			int lineNumber = 0;
			while ((line = reader.readLine()) != null) {
				lineNumber++;
				if (line.isBlank()) {
					continue;
				}
				Entry entry = MAPPER.readValue(line, Entry.class);
				if (cache.putIfAbsent(entry.original(), entry.toTransformedQuery()) != null) {
					throw new IllegalStateException(file + ":" + lineNumber + ": 같은 질문이 두 번 저장돼 있습니다: " + entry.original());
				}
			}
		} catch (IOException exception) {
			throw new UncheckedIOException("저장된 질문 변환 결과를 읽지 못했습니다: " + file, exception);
		}
	}
}
