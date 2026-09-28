package com.vita.faq.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Order(100)
@ConditionalOnProperty(prefix = "faq.import", name = "enabled", havingValue = "true")
public class FaqDataImportRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(FaqDataImportRunner.class);

	private final FaqJsonlReader reader;
	private final FaqDataImporter importer;
	private final ResourceLoader resourceLoader;
	private final String resourceLocation;

	public FaqDataImportRunner(
		FaqJsonlReader reader,
		FaqDataImporter importer,
		ResourceLoader resourceLoader,
		@Value("${faq.import.resource}") String resourceLocation
	) {
		this.reader = reader;
		this.importer = importer;
		this.resourceLoader = resourceLoader;
		this.resourceLocation = resourceLocation;
	}

	@Override
	public void run(ApplicationArguments args) {
		Resource resource = resourceLoader.getResource(resourceLocation);
		List<FaqJsonlRecord> faqs = reader.read(resource);
		int count = importer.importFaqs(faqs);
		log.info("FAQ JSONL 적재 완료: resource={}, count={}", resourceLocation, count);
	}
}
