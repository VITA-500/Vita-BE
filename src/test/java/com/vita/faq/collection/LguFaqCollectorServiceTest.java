package com.vita.faq.collection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class LguFaqCollectorServiceTest {

	private static final LguFaqCategorySelection MOBILE_PLAN = new LguFaqCategorySelection(
		"NODE-MOBILE", "모바일", "NODE-PLAN", "요금제"
	);

	@TempDir
	Path tempDirectory;

	private final LguFaqApiClient apiClient = mock(LguFaqApiClient.class);
	private final LguFaqTransformer transformer = mock(LguFaqTransformer.class);
	private final LguFaqJsonlWriter writer = mock(LguFaqJsonlWriter.class);
	private final LguFaqCollectorService service = new LguFaqCollectorService(
		apiClient,
		transformer,
		new LguFaqDeduplicator(),
		writer
	);

	@Test
	void traversesMainAndSubcategoryPagesThenWritesJsonl() {
		stubMobilePlanCategory();
		when(apiClient.fetchCategoryPage("NODE-MOBILE", "NODE-PLAN", 1, 10))
			.thenReturn(page(1, 2, "KNOW-1", "KNOW-2"));
		when(apiClient.fetchCategoryPage("NODE-MOBILE", "NODE-PLAN", 2, 10))
			.thenReturn(page(2, 2, "KNOW-1", "KNOW-3"));
		when(apiClient.fetchDetail("KNOW-1")).thenReturn(detail("KNOW-1"));
		when(apiClient.fetchDetail("KNOW-2")).thenReturn(detail("KNOW-2"));
		when(apiClient.fetchDetail("KNOW-3")).thenReturn(detail("KNOW-3"));
		when(transformer.transform(any(), any())).thenAnswer(invocation -> {
			LguFaqDetailResponse detail = invocation.getArgument(0);
			LguFaqCategorySelection selection = invocation.getArgument(1);
			assertThat(selection).isEqualTo(MOBILE_PLAN);
			return Optional.of(faq(detail.kbId()));
		});
		Path output = tempDirectory.resolve("faq_all_cleaned.jsonl");

		LguFaqCollectionSummary summary = service.collect(
			new LguFaqCollectionRequest(" 모바일 ", 10, 1, 1, 2, 3, 0, output)
		);

		assertThat(summary.visitedMainCategories()).isEqualTo(1);
		assertThat(summary.visitedSubcategories()).isEqualTo(1);
		assertThat(summary.fetchedPages()).isEqualTo(2);
		assertThat(summary.listedFaqs()).isEqualTo(4);
		assertThat(summary.requestedDetails()).isEqualTo(3);
		assertThat(summary.collectedFaqs()).isEqualTo(3);
		assertThat(summary.duplicateSourceFaqIds()).containsExactly("KNOW-1");
		verify(writer).write(output, List.of(faq("KNOW-1"), faq("KNOW-2"), faq("KNOW-3")));
	}

	@Test
	void continuesWhenOneDetailRequestFails() {
		stubMobilePlanCategory();
		when(apiClient.fetchCategoryPage("NODE-MOBILE", "NODE-PLAN", 1, 10))
			.thenReturn(page(1, 1, "KNOW-1", "KNOW-2"));
		when(apiClient.fetchDetail("KNOW-1"))
			.thenThrow(new LguFaqCollectionException("상세 호출 실패"));
		when(apiClient.fetchDetail("KNOW-2")).thenReturn(detail("KNOW-2"));
		when(transformer.transform(any(), any())).thenReturn(Optional.of(faq("KNOW-2")));
		Path output = tempDirectory.resolve("faq_all_cleaned.jsonl");

		LguFaqCollectionSummary summary = service.collect(
			new LguFaqCollectionRequest("", 10, 1, 1, 1, 2, 0, output)
		);

		assertThat(summary.failedDetailIds()).containsExactly("KNOW-1");
		assertThat(summary.collectedFaqs()).isEqualTo(1);
		verify(writer).write(output, List.of(faq("KNOW-2")));
	}

	@Test
	void recordsFaqRejectedByTransformer() {
		stubMobilePlanCategory();
		when(apiClient.fetchCategoryPage("NODE-MOBILE", "NODE-PLAN", 1, 10))
			.thenReturn(page(1, 1, "KNOW-1"));
		when(apiClient.fetchDetail("KNOW-1")).thenReturn(detail("KNOW-1"));
		when(transformer.transform(any(), any())).thenReturn(Optional.empty());

		LguFaqCollectionRequest request = new LguFaqCollectionRequest(
			"", 10, 1, 1, 1, 1, 0, tempDirectory.resolve("faq_all_cleaned.jsonl")
		);

		LguFaqCollectionSummary summary = service.collect(request);

		assertThat(summary.collectedFaqs()).isZero();
		assertThat(summary.skippedFaqIds()).containsExactly("KNOW-1");
		verify(writer).readExisting(request.output());
		verify(writer, never()).write(any(), any());
	}

	private void stubMobilePlanCategory() {
		when(apiClient.fetchRootCategories(10)).thenReturn(categoriesAtDepth(0,
			new LguFaqCategoryTabItem("TOP10", "TOP10"),
			new LguFaqCategoryTabItem("NODE-MOBILE", "모바일")
		));
		when(apiClient.fetchMainCategory("NODE-MOBILE", 10)).thenReturn(categoriesAtDepth(1,
			new LguFaqCategoryTabItem("ALL", "전체"),
			new LguFaqCategoryTabItem("NODE-PLAN", "요금제")
		));
	}

	private LguFaqListResponse categoriesAtDepth(int depth, LguFaqCategoryTabItem... categories) {
		List<List<LguFaqCategoryTabItem>> tabs = depth == 0
			? List.of(List.of(categories))
			: List.of(List.of(), List.of(categories));
		return new LguFaqListResponse(new LguFaqPageInfo(10, 0, 1, 1, 0), List.of(), tabs);
	}

	private LguFaqListResponse page(int pageNo, int totalPages, String... ids) {
		List<LguFaqListItem> items = java.util.Arrays.stream(ids)
			.map(id -> new LguFaqListItem(id, "질문", "NODE", "분류", "20260918", "경로"))
			.toList();
		return new LguFaqListResponse(
			new LguFaqPageInfo(10, items.size(), pageNo, totalPages, items.size()),
			items
		);
	}

	private LguFaqDetailResponse detail(String id) {
		return new LguFaqDetailResponse(
			id, "질문", "답변", "NODE", List.of("개인", "모바일", "요금제"),
			"개인 > 모바일 > 요금제"
		);
	}

	private LguFaqCollectedRecord faq(String id) {
		return new LguFaqCollectedRecord(
			id, "모바일", "요금제", "질문 " + id, "답변", "개인 > 모바일 > 요금제"
		);
	}
}
