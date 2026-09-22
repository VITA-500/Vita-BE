package com.vita.faq.collection;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LguFaqCollectionRunnerTest {

	@Test
	void runsCollectionWithConfiguredOptions() throws Exception {
		LguFaqCollectorService collectorService = mock(LguFaqCollectorService.class);
		Path output = Path.of("build/faq/test.jsonl");
		LguFaqCollectionRequest expectedRequest = new LguFaqCollectionRequest(
			"모바일", 20, 2, 3, 4, 5, 750, output
		);
		when(collectorService.collect(expectedRequest)).thenReturn(new LguFaqCollectionSummary(
			2, 3, 10, 5, 4, 4, List.of("KNOW-FAIL"), List.of("KNOW-SKIP"), Set.of("KNOW-DUP"), 1, output
		));
		LguFaqCollectionRunner runner = new LguFaqCollectionRunner(
			collectorService, "모바일", 20, 2, 3, 4, 5, 750, output.toString()
		);

		runner.run(new DefaultApplicationArguments());

		verify(collectorService).collect(expectedRequest);
	}
}
