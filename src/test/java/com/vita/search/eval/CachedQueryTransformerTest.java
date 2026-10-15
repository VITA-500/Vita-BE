package com.vita.search.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vita.search.pipeline.QueryTransformer;
import com.vita.search.pipeline.TransformInfo;
import com.vita.search.pipeline.TransformedQuery;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CachedQueryTransformerTest {

	@TempDir
	Path dir;

	/** 부른 질문을 기록하고, 정해 둔 변환 기록을 붙여 돌려주는 가짜 변환기. */
	private static class FakeTransformer implements QueryTransformer {
		final List<String> calls = new ArrayList<>();
		TransformInfo info = TransformInfo.changed();

		@Override
		public TransformedQuery transform(String query) {
			calls.add(query);
			return new TransformedQuery(query, "faq:" + query, "plan:" + query, info);
		}
	}

	@Test
	void savedResultsAreReusedWithoutCallingTheTransformer() {
		Path file = dir.resolve("transform.jsonl");
		CachedQueryTransformer cached = new CachedQueryTransformer(new FakeTransformer(), file);
		TransformedQuery fresh = cached.transform("로밍");
		cached.save();

		FakeTransformer second = new FakeTransformer();
		CachedQueryTransformer reloaded = new CachedQueryTransformer(second, file);
		TransformedQuery again = reloaded.transform("로밍");

		assertThat(again).isEqualTo(fresh);
		assertThat(second.calls).isEmpty();
		assertThat(reloaded.hits()).isEqualTo(1);
		assertThat(reloaded.misses()).isZero();
	}

	@Test
	void changeInfoSurvivesTheRoundTrip() {
		Path file = dir.resolve("transform.jsonl");
		FakeTransformer fake = new FakeTransformer();
		fake.info = TransformInfo.partialNull(false, true);
		CachedQueryTransformer cached = new CachedQueryTransformer(fake, file);
		cached.transform("유심");
		cached.save();

		TransformedQuery loaded = new CachedQueryTransformer(new FakeTransformer(), file).transform("유심");

		assertThat(loaded.info()).isEqualTo(TransformInfo.partialNull(false, true));
		assertThat(loaded.faqQuery()).isEqualTo("faq:유심");
	}

	@Test
	void callFailedFallbacksAreNotStoredButOtherFallbacksAre() {
		Path file = dir.resolve("transform.jsonl");
		FakeTransformer fake = new FakeTransformer();
		CachedQueryTransformer cached = new CachedQueryTransformer(fake, file);

		fake.info = TransformInfo.fallback(TransformInfo.REASON_CALL_FAILED);
		cached.transform("a");
		fake.info = TransformInfo.fallback(TransformInfo.REASON_PARSE_FAILED);
		cached.transform("b");
		cached.save();

		assertThat(cached.skippedTransient()).isEqualTo(1);
		assertThat(cached.size()).isEqualTo(1);

		CachedQueryTransformer reloaded = new CachedQueryTransformer(fake, file);
		reloaded.transform("a");
		reloaded.transform("b");
		assertThat(reloaded.misses()).isEqualTo(1);
		assertThat(reloaded.hits()).isEqualTo(1);
	}

	@Test
	void fileIsNotRewrittenWhenEverythingCameFromTheCache() throws IOException {
		Path file = dir.resolve("transform.jsonl");
		CachedQueryTransformer cached = new CachedQueryTransformer(new FakeTransformer(), file);
		cached.transform("a");
		cached.save();
		Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(1_000_000L));

		CachedQueryTransformer reloaded = new CachedQueryTransformer(new FakeTransformer(), file);
		reloaded.transform("a");
		reloaded.save();

		assertThat(Files.getLastModifiedTime(file).toMillis()).isEqualTo(1_000_000L);
	}

	@Test
	void duplicateQuestionInTheFileIsRejected() throws IOException {
		Path file = dir.resolve("transform.jsonl");
		String line = "{\"original\":\"a\",\"faqQuery\":\"a\",\"planQuery\":\"a\",\"kind\":\"CHANGED\",\"reason\":\"\",\"faqNull\":false,\"planNull\":false}";
		Files.writeString(file, line + "\n" + line + "\n", StandardCharsets.UTF_8);

		assertThatThrownBy(() -> new CachedQueryTransformer(new FakeTransformer(), file))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("두 번 저장");
	}
}
