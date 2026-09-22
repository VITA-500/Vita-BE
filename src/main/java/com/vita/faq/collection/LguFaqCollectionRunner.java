package com.vita.faq.collection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** 설정값에 따라 LG U+ FAQ 수집과 JSONL 출력을 한 번 실행한다. */
@Component
@Order(50)
@ConditionalOnProperty(prefix = "faq.collection", name = "enabled", havingValue = "true")
public class LguFaqCollectionRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(LguFaqCollectionRunner.class);

	private final LguFaqCollectorService collectorService;
	private final LguFaqCollectionRequest request;

	public LguFaqCollectionRunner(
		LguFaqCollectorService collectorService,
		@Value("${faq.collection.main-category:}") String mainCategory,
		@Value("${faq.collection.page-size:10}") int pageSize,
		@Value("${faq.collection.max-main-categories:1}") int maxMainCategories,
		@Value("${faq.collection.max-subcategories-per-main:1}") int maxSubcategoriesPerMain,
		@Value("${faq.collection.max-pages-per-subcategory:1}") int maxPagesPerSubcategory,
		@Value("${faq.collection.max-details:10}") int maxDetails,
		@Value("${faq.collection.request-delay-ms:500}") long requestDelayMillis,
		@Value("${faq.collection.output:build/faq/faq_all_cleaned.jsonl}") String output
	) {
		this.collectorService = collectorService;
		this.request = new LguFaqCollectionRequest(
			mainCategory,
			pageSize,
			maxMainCategories,
			maxSubcategoriesPerMain,
			maxPagesPerSubcategory,
			maxDetails,
			requestDelayMillis,
			Path.of(output)
		);
	}

	@Override
	public void run(ApplicationArguments args) {
		LguFaqCollectionSummary summary = collectorService.collect(request);
		log.info(
			"LG U+ FAQ 수집 완료: mainCategories={}, subcategories={}, pages={}, listed={}, requestedDetails={}, collected={}, duplicateIds={}, duplicateQuestions={}, output={}",
			summary.visitedMainCategories(),
			summary.visitedSubcategories(),
			summary.fetchedPages(),
			summary.listedFaqs(),
			summary.requestedDetails(),
			summary.collectedFaqs(),
			summary.duplicateSourceFaqIds().size(),
			summary.duplicateQuestionGroups(),
			summary.output()
		);
		if (!summary.failedDetailIds().isEmpty()) {
			log.warn(
				"LG U+ FAQ 상세 조회 실패: count={}, firstIds={}",
				summary.failedDetailIds().size(),
				summary.failedDetailIds().stream().limit(20).toList()
			);
		}
		if (!summary.skippedFaqIds().isEmpty()) {
			log.warn("LG U+ FAQ 정제·매핑 제외: count={}, ids={}", summary.skippedFaqIds().size(), summary.skippedFaqIds());
		}
	}
}
