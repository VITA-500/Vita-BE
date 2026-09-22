package com.vita.faq.collection;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** LG U+ FAQ 목록·상세 조회부터 정제된 JSONL 생성까지 실행한다. */
@Service
public class LguFaqCollectorService {
	private static final Logger log = LoggerFactory.getLogger(LguFaqCollectorService.class);
	private static final int CHECKPOINT_INTERVAL = 25;

	private final LguFaqApiClient apiClient;
	private final LguFaqTransformer transformer;
	private final LguFaqDeduplicator deduplicator;
	private final LguFaqJsonlWriter jsonlWriter;

	public LguFaqCollectorService(
		LguFaqApiClient apiClient,
		LguFaqTransformer transformer,
		LguFaqDeduplicator deduplicator,
		LguFaqJsonlWriter jsonlWriter
	) {
		this.apiClient = apiClient;
		this.transformer = transformer;
		this.deduplicator = deduplicator;
		this.jsonlWriter = jsonlWriter;
	}

	public LguFaqCollectionSummary collect(LguFaqCollectionRequest request) {
		RequestThrottle throttle = new RequestThrottle(request.requestDelayMillis());
		Map<String, LguFaqCategorySelection> targets = new LinkedHashMap<>();
		Set<String> duplicateSourceIds = new LinkedHashSet<>();

		LguFaqListResponse root = executeWithRateLimitRetry(
			throttle, () -> apiClient.fetchRootCategories(request.pageSize())
		);
		List<LguFaqCategoryTabItem> mainCategories = root.categoriesAt(0).stream()
			.filter(this::isCollectableCategory)
			.filter(category -> request.mainCategory().isBlank()
				|| category.name().equals(request.mainCategory()))
			.limit(request.maxMainCategories())
			.toList();

		int visitedMainCategories = 0;
		int visitedSubcategories = 0;
		int fetchedPages = 0;
		int listedFaqs = 0;

		for (LguFaqCategoryTabItem mainCategory : mainCategories) {
			LguFaqListResponse mainPage = executeWithRateLimitRetry(
				throttle, () -> apiClient.fetchMainCategory(mainCategory.id(), request.pageSize())
			);
			visitedMainCategories++;
			List<LguFaqCategoryTabItem> subcategories = mainPage.categoriesAt(1).stream()
				.filter(this::isCollectableCategory)
				.limit(request.maxSubcategoriesPerMain())
				.toList();

			for (LguFaqCategoryTabItem subcategory : subcategories) {
				visitedSubcategories++;
				LguFaqCategorySelection selection = new LguFaqCategorySelection(
					mainCategory.id(), mainCategory.name(), subcategory.id(), subcategory.name()
				);
				for (int pageNo = 1; pageNo <= request.maxPagesPerSubcategory(); pageNo++) {
					int requestedPage = pageNo;
					LguFaqListResponse page = executeWithRateLimitRetry(
						throttle,
						() -> apiClient.fetchCategoryPage(
							mainCategory.id(), subcategory.id(), requestedPage, request.pageSize()
						)
					);
					fetchedPages++;
					listedFaqs += page.items().size();
					addTargets(page.items(), selection, targets, duplicateSourceIds);
					if (pageNo >= page.listPageInfo().totalPage()) {
						break;
					}
				}
			}
		}

		List<LguFaqCollectedRecord> existing = jsonlWriter.readExisting(request.output());
		if (existing == null) {
			existing = List.of();
		}
		List<LguFaqCollectedRecord> collected = new ArrayList<>(existing);
		Set<String> completedSourceIds = new LinkedHashSet<>();
		existing.forEach(faq -> completedSourceIds.add(faq.sourceFaqId()));
		List<String> failedDetailIds = new ArrayList<>();
		List<String> skippedFaqIds = new ArrayList<>();
		int requestedDetails = 0;
		for (Map.Entry<String, LguFaqCategorySelection> target : targets.entrySet()) {
			if (completedSourceIds.contains(target.getKey())) {
				continue;
			}
			if (requestedDetails >= request.maxDetails()) {
				break;
			}
			String sourceFaqId = target.getKey();
			requestedDetails++;
			try {
				transformer.transform(
					executeWithRateLimitRetry(throttle, () -> apiClient.fetchDetail(sourceFaqId)),
					target.getValue()
				).ifPresentOrElse(faq -> {
					collected.add(faq);
					completedSourceIds.add(sourceFaqId);
					if (collected.size() % CHECKPOINT_INTERVAL == 0) {
						jsonlWriter.write(request.output(), collected);
					}
				}, () -> skippedFaqIds.add(sourceFaqId));
			} catch (LguFaqCollectionException exception) {
				failedDetailIds.add(sourceFaqId);
			}
		}

		LguFaqDeduplicationResult deduplication = deduplicator.deduplicate(collected);
		duplicateSourceIds.addAll(deduplication.duplicateSourceFaqIds());
		if (!deduplication.uniqueFaqs().isEmpty()) {
			jsonlWriter.write(request.output(), deduplication.uniqueFaqs());
		}

		return new LguFaqCollectionSummary(
			visitedMainCategories,
			visitedSubcategories,
			fetchedPages,
			listedFaqs,
			requestedDetails,
			deduplication.uniqueFaqs().size(),
			List.copyOf(failedDetailIds),
			List.copyOf(skippedFaqIds),
			Collections.unmodifiableSet(new LinkedHashSet<>(duplicateSourceIds)),
			deduplication.duplicateQuestionCandidates().size(),
			request.output()
		);
	}

	private <T> T executeWithRateLimitRetry(RequestThrottle throttle, Supplier<T> request) {
		while (true) {
			throttle.beforeRequest();
			try {
				return request.get();
			} catch (LguFaqRateLimitException exception) {
				long waitMillis = Math.multiplyExact(exception.retryAfterSeconds() + 1, 1_000);
				log.warn("LG U+ 요청 제한: {}초 후 자동 재시도합니다.", exception.retryAfterSeconds());
				RequestThrottle.waitFor(waitMillis);
			}
		}
	}

	private boolean isCollectableCategory(LguFaqCategoryTabItem category) {
		return category != null && category.id() != null && !category.id().isBlank()
			&& category.name() != null && !category.name().isBlank()
			&& !"TOP10".equals(category.id()) && !"ALL".equals(category.id());
	}

	private void addTargets(
		List<LguFaqListItem> items,
		LguFaqCategorySelection selection,
		Map<String, LguFaqCategorySelection> targets,
		Set<String> duplicateSourceIds
	) {
		for (LguFaqListItem item : items) {
			if (item == null || item.kbId() == null || item.kbId().isBlank()) {
				continue;
			}
			String sourceFaqId = item.kbId().strip();
			if (targets.putIfAbsent(sourceFaqId, selection) != null) {
				duplicateSourceIds.add(sourceFaqId);
			}
		}
	}

	private static final class RequestThrottle {

		private final long delayMillis;
		private boolean firstRequest = true;

		private RequestThrottle(long delayMillis) {
			this.delayMillis = delayMillis;
		}

		private void beforeRequest() {
			if (firstRequest) {
				firstRequest = false;
				return;
			}
			if (delayMillis == 0) {
				return;
			}
			waitFor(delayMillis);
		}

		private static void waitFor(long millis) {
			try {
				Thread.sleep(millis);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new LguFaqCollectionException("FAQ 수집 요청 대기 중 중단되었습니다.", exception);
			}
		}
	}
}
